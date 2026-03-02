package org.greenbuttonalliance.espi.thirdparty.web;

import org.greenbuttonalliance.espi.common.domain.Authorization;
import org.greenbuttonalliance.espi.common.service.AuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.servlet.http.HttpServletResponse;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/server")
public class ServerProxyController {

    private static final Logger logger = LoggerFactory.getLogger(ServerProxyController.class);

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RestTemplate restTemplate;

    // Shared secret for internal server-to-server calls (dev default)
    private final String internalSecret = System.getenv().getOrDefault("INTERNAL_SHARED_SECRET", "dev-internal-secret");

    // Optional: write fetched XML to disk (off by default; can be expensive for large payloads)
    private final boolean persistXml = "true".equalsIgnoreCase(System.getenv().getOrDefault("TP_PERSIST_XML", "false"));

    // Safety valve: IntervalReading feeds can be enormous (hundreds of thousands of rows).
    // Limit how many IntervalBlock feeds we aggregate into the response.
    // Set to 0 to disable the limit.
    private final int maxIntervalBlockFeeds = Integer.parseInt(System.getenv().getOrDefault("TP_MAX_INTERVAL_BLOCK_FEEDS", "1"));

    // Tiny in-memory cache to avoid repeated large downloads when the FE re-mounts/retries.
    // Keyed by retailCustomerId and authorization (accessToken+resourceURI).
    private final Map<String, CachedXml> cache = new ConcurrentHashMap<>();
    private final long cacheTtlMs = Long.parseLong(System.getenv().getOrDefault("TP_XML_CACHE_TTL_MS", "5000"));

        // Extract IntervalBlock collection links from a Subscription/UsagePoint feed.
        // Attribute order in <link> is not stable (sometimes href comes before rel), so match both.
        private static final Pattern INTERVAL_BLOCK_LINK = Pattern.compile(
            "<link\\b(?:(?:[^>]*\\brel=\\\"related\\\"[^>]*\\bhref=\\\"([^\\\"]+/IntervalBlock)\\\")|(?:[^>]*\\bhref=\\\"([^\\\"]+/IntervalBlock)\\\"[^>]*\\brel=\\\"related\\\"))",
            Pattern.CASE_INSENSITIVE);

    private static boolean containsIntervalBlocks(String xml) {
        if (xml == null) return false;
        return xml.contains(":IntervalBlock") || xml.contains("<IntervalBlock");
    }

    private static String buildAggregateIntervalBlockFeed(List<String> intervalBlockFeeds, String privacyLevel) {
        List<String> entries = new ArrayList<>();
        Pattern entryPattern = Pattern.compile("(?s)<entry\\b.*?</entry>");

        for (String feed : intervalBlockFeeds) {
            if (feed == null) continue;
            Matcher m = entryPattern.matcher(feed);
            while (m.find()) {
                entries.add(m.group());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<?xml-stylesheet type=\"text/xsl\" href=\"GreenButtonDataStyleSheet.xslt\"?>\n");
        sb.append("<feed xmlns=\"http://www.w3.org/2005/Atom\" xmlns:espi=\"http://naesb.org/espi\">\n");
        sb.append("  <id>urn:uuid:").append(UUID.randomUUID()).append("</id>\n");
        sb.append("  <title>Green Button IntervalBlock Feed - Privacy Level: ").append(privacyLevel != null ? privacyLevel : "unknown").append("</title>\n");
        sb.append("  <updated>").append(Instant.now().toString()).append("</updated>\n");
        for (String entry : entries) {
            sb.append(entry).append("\n");
        }
        sb.append("</feed>");
        return sb.toString();
    }

    private static final class CachedXml {
        final long at;
        final String body;
        final org.springframework.http.MediaType contentType;

        CachedXml(long at, String body, org.springframework.http.MediaType contentType) {
            this.at = at;
            this.body = body;
            this.contentType = contentType;
        }
    }

    public static final class AuthorizationStatus {
        public boolean authorized;
        public String privacyLevel;
        public AuthorizationStatus(boolean authorized, String privacyLevel) {
            this.authorized = authorized;
            this.privacyLevel = privacyLevel;
        }
    }

    public static final class UsagePointRecord {
        public String meterId;
        public String timestamp;
        public double usageKw;

        public UsagePointRecord(String meterId, String timestamp, double usageKw) {
            this.meterId = meterId;
            this.timestamp = timestamp;
            this.usageKw = usageKw;
        }
    }

    private boolean isInternalAuthorized(String providedSecret) {
        return providedSecret != null && providedSecret.equals(internalSecret);
    }

    private Path findCommonRoot() {
        String explicit = System.getenv("OPENESPI_COMMON_DIR");
        if (explicit != null && !explicit.isBlank()) {
            Path p = Paths.get(explicit).toAbsolutePath().normalize();
            if (Files.exists(p.resolve("bin/db_graph.py"))) return p;
        }

        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path[] candidates = new Path[] {
                cwd.resolve("../OpenESPI-Common-java").normalize(),
                cwd.resolve("../../OpenESPI-Common-java").normalize(),
                Paths.get("/GBA/OpenESPI-Common-java").toAbsolutePath().normalize(),
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate.resolve("bin/db_graph.py"))) return candidate;
        }
        return null;
    }

    private void regenerateGraphs(Path commonRoot) throws Exception {
        Path script = commonRoot.resolve("bin/db_graph.py");
        Path graphsDir = commonRoot.resolve("docs/graphs");
        Files.createDirectories(graphsDir);

        ProcessBuilder schemaPb = new ProcessBuilder(
                "python3",
                script.toString(),
                "schema",
                "--schema",
                "datacustodian",
                "--filter",
                "usage_points|usage_point_related_links|meter_readings|interval_blocks|subscriptions|retail_customers|authorization",
                "--out-dot",
                graphsDir.resolve("dc_schema_focus.dot").toString(),
                "--out-mmd",
                graphsDir.resolve("dc_schema_focus.mmd").toString()
        );
        schemaPb.directory(commonRoot.toFile());
            schemaPb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            schemaPb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process schemaProc = schemaPb.start();
            int schemaExit = schemaProc.waitFor();
            if (schemaExit != 0) {
                throw new IllegalStateException("db_graph.py schema exited " + schemaExit);
            }

        ProcessBuilder privacyPb = new ProcessBuilder(
                "python3",
                script.toString(),
                "privacy",
                "--db",
                "datacustodian",
                "--out-dot",
                graphsDir.resolve("dc_privacy.dot").toString(),
                "--out-mmd",
                graphsDir.resolve("dc_privacy.mmd").toString()
        );
        privacyPb.directory(commonRoot.toFile());
        privacyPb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        privacyPb.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process privacyProc = privacyPb.start();
        int privacyExit = privacyProc.waitFor();
        if (privacyExit != 0) {
            throw new IllegalStateException("db_graph.py privacy exited " + privacyExit);
        }
    }

    @GetMapping("/retailCustomer/{id}/authorizationStatus")
    public ResponseEntity<AuthorizationStatus> authorizationStatus(
            @PathVariable("id") Long retailCustomerId,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret
    ) {
        if (!isInternalAuthorized(providedSecret)) {
            return ResponseEntity.status(401).build();
        }

        List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(retailCustomerId);
        if (authorizations == null || authorizations.isEmpty()) {
            return ResponseEntity.ok(new AuthorizationStatus(false, null));
        }
        Authorization authorization = authorizations.stream()
                .max(Comparator.comparing(
                        Authorization::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(authorizations.get(0));

        boolean authorized = authorization.getAccessToken() != null;
        return ResponseEntity.ok(new AuthorizationStatus(authorized, authorization.getPrivacyLevel()));
    }

    @GetMapping("/retailCustomer/{id}/graphs/refresh")
    public ResponseEntity<String> refreshGraphs(
            @PathVariable("id") Long retailCustomerId,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret
    ) {
        if (!isInternalAuthorized(providedSecret)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(retailCustomerId);
        if (authorizations == null || authorizations.isEmpty()) {
            return ResponseEntity.status(404).body("No authorization found for retail customer");
        }
        Authorization authorization = authorizations.stream()
                .max(Comparator.comparing(
                        Authorization::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(authorizations.get(0));
        if (authorization.getAccessToken() == null) {
            return ResponseEntity.status(409).body("Authorization exists but no access token stored yet");
        }

        try {
            Path commonRoot = findCommonRoot();
            if (commonRoot == null) {
                return ResponseEntity.status(500).body("Could not locate OpenESPI-Common-java (set OPENESPI_COMMON_DIR)");
            }
            regenerateGraphs(commonRoot);
            return ResponseEntity.ok("OK");
        } catch (Exception e) {
            logger.error("Failed to regenerate graphs", e);
            return ResponseEntity.status(500).body("Failed to regenerate graphs: " + e.getMessage());
        }
    }

    @GetMapping("/graphs/{name:.+}")
    public ResponseEntity<String> getGraphFile(
            @PathVariable("name") String name,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret
    ) {
        if (!isInternalAuthorized(providedSecret)) {
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Tomcat can append matrix params like ";jsessionid=..." into the URI path.
        // Strip those so our whitelist and file resolution behave predictably.
        String cleanName = name;
        int semi = cleanName.indexOf(';');
        if (semi >= 0) {
            cleanName = cleanName.substring(0, semi);
        }

            // Support both extensionless keys and explicit .mmd filenames.
            String resolvedName = cleanName;
            if (!resolvedName.endsWith(".mmd")) {
                resolvedName = resolvedName + ".mmd";
            }

            Set<String> allowed = Set.of(
                    "dc_schema_focus.mmd",
                    "dc_privacy.mmd",
                    "flow_consent_to_data.mmd",
                    "flow_privacy_variant_selection.mmd"
            );
            if (!allowed.contains(resolvedName)) {
            return ResponseEntity.status(404).body("Unknown graph");
        }

        try {
            Path commonRoot = findCommonRoot();
            if (commonRoot == null) {
                return ResponseEntity.status(500).body("Could not locate OpenESPI-Common-java (set OPENESPI_COMMON_DIR)");
            }
            Path graphsDir = commonRoot.resolve("docs/graphs");
            Path file = graphsDir.resolve(resolvedName).normalize();
            if (!file.startsWith(graphsDir) || !Files.exists(file)) {
                return ResponseEntity.status(404).body("Missing graph file");
            }
            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(MediaType.TEXT_PLAIN);
            return ResponseEntity.ok().headers(respHeaders).body(Files.readString(file));
        } catch (Exception e) {
            logger.error("Failed to read graph file {}", name, e);
            return ResponseEntity.status(500).body("Failed to read graph file: " + e.getMessage());
        }
    }

    // Raw variant that bypasses Spring's message converters / content negotiation.
    // Some legacy configurations will still 406 on ".mmd" even if we set Content-Type.
    @GetMapping("/graphsRaw/{name:.+}")
    public void getGraphFileRaw(
            @PathVariable("name") String name,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret,
            HttpServletResponse response
    ) {
        try {
            if (!isInternalAuthorized(providedSecret)) {
                response.setStatus(401);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Unauthorized");
                return;
            }

            Set<String> allowed = Set.of(
                    "dc_schema_focus.mmd",
                    "dc_privacy.mmd",
                    "flow_consent_to_data.mmd",
                    "flow_privacy_variant_selection.mmd"
            );
            if (!allowed.contains(name)) {
                response.setStatus(404);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Unknown graph");
                return;
            }

            Path commonRoot = findCommonRoot();
            if (commonRoot == null) {
                response.setStatus(500);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Could not locate OpenESPI-Common-java (set OPENESPI_COMMON_DIR)");
                return;
            }
            Path graphsDir = commonRoot.resolve("docs/graphs");
            Path file = graphsDir.resolve(name).normalize();
            if (!file.startsWith(graphsDir) || !Files.exists(file)) {
                response.setStatus(404);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Missing graph file");
                return;
            }

            response.setStatus(200);
            response.setContentType("text/plain;charset=UTF-8");
            try (Writer writer = response.getWriter()) {
                writer.write(Files.readString(file));
            }
        } catch (Exception e) {
            logger.error("Failed to stream graph file {}", name, e);
            try {
                response.setStatus(500);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write("Failed to read graph file: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    // Dotless graph-key endpoint to avoid extension-based content negotiation issues.
    @GetMapping("/graph/{key}")
    public ResponseEntity<String> getGraphByKey(
            @PathVariable("key") String key,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret
    ) {
        Map<String, String> keyToFile = Map.of(
                "dc_schema_focus", "dc_schema_focus.mmd",
                "dc_privacy", "dc_privacy.mmd",
                "flow_consent_to_data", "flow_consent_to_data.mmd",
                "flow_privacy_variant_selection", "flow_privacy_variant_selection.mmd"
        );
        String file = keyToFile.get(key);
        if (file == null) {
            return ResponseEntity.status(404).body("Unknown graph");
        }
        return getGraphFile(file, providedSecret);
    }

    @GetMapping("/retailCustomer/{id}/dataPoints")
    public ResponseEntity<?> fetchRetailCustomerDataPoints(
            @PathVariable("id") Long retailCustomerId,
            @RequestParam(value = "limit", required = false, defaultValue = "5000") int limit,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret) {

        if (!isInternalAuthorized(providedSecret)) {
            logger.warn("Unauthorized internal dataPoints attempt for retailCustomer {}", retailCustomerId);
            return ResponseEntity.status(401).body("Unauthorized");
        }

        // Reuse the existing XML endpoint logic by calling it internally.
        ResponseEntity<String> xmlResp = fetchRetailCustomerData(retailCustomerId, null, providedSecret);
        if (!xmlResp.getStatusCode().is2xxSuccessful()) {
            return ResponseEntity.status(xmlResp.getStatusCode()).body(xmlResp.getBody());
        }
        String xml = xmlResp.getBody();
        if (xml == null || xml.isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        // Determine privacy level from latest authorization (for meterId suffix)
        String privacyLevel = null;
        try {
            List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(retailCustomerId);
            if (authorizations != null && !authorizations.isEmpty()) {
                Authorization authorization = authorizations.stream()
                        .max(Comparator.comparing(
                                Authorization::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .orElse(authorizations.get(0));
                privacyLevel = authorization.getPrivacyLevel();
            }
        } catch (Exception ignored) {
        }

        List<UsagePointRecord> out = new ArrayList<>();
        String meterId = "Usage Point" + (privacyLevel != null ? (" - " + privacyLevel) : "");

        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);

            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(xml));
            boolean inTimePeriod = false;
            Long currentStart = null;
            Double currentValueWh = null;

            while (reader.hasNext() && out.size() < limit) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("timePeriod".equalsIgnoreCase(local)) {
                        inTimePeriod = true;
                    } else if (inTimePeriod && "start".equalsIgnoreCase(local)) {
                        String text = reader.getElementText();
                        try {
                            currentStart = Long.parseLong(text.trim());
                        } catch (Exception ignored) {
                            currentStart = null;
                        }
                    } else if ("value".equalsIgnoreCase(local)) {
                        String text = reader.getElementText();
                        try {
                            currentValueWh = Double.parseDouble(text.trim());
                        } catch (Exception ignored) {
                            currentValueWh = null;
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("timePeriod".equalsIgnoreCase(local)) {
                        inTimePeriod = false;
                    } else if ("IntervalReading".equalsIgnoreCase(local)) {
                        if (currentStart != null && currentValueWh != null) {
                            String ts = Instant.ofEpochSecond(currentStart).toString();
                            out.add(new UsagePointRecord(meterId, ts, currentValueWh / 1000.0));
                        }
                        currentStart = null;
                        currentValueWh = null;
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing IntervalReadings for retailCustomer {}", retailCustomerId, e);
            return ResponseEntity.status(500).body("Failed to parse XML: " + e.getMessage());
        }

        return ResponseEntity.ok(out);
    }

    @GetMapping("/retailCustomer/{id}/data")
    public ResponseEntity<String> fetchRetailCustomerData(
            @PathVariable("id") Long retailCustomerId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-INTERNAL-SECRET", required = false) String providedSecret) {

        if (providedSecret == null || !providedSecret.equals(internalSecret)) {
            logger.warn("Unauthorized internal fetch attempt for retailCustomer {}", retailCustomerId);
            return ResponseEntity.status(401).body("Unauthorized");
        }

        try {
            List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(retailCustomerId);
            if (authorizations == null || authorizations.isEmpty()) {
                return ResponseEntity.status(404).body("No authorization found for retail customer");
            }

            // Use the most recent authorization; the list order is not guaranteed.
            Authorization authorization = authorizations.stream()
                    .max(Comparator.comparing(
                            Authorization::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .orElse(authorizations.get(0));
            String accessToken = authorization.getAccessToken();
            String privacyLevel = authorization.getPrivacyLevel();
            if (accessToken == null) {
                return ResponseEntity.status(404).body("No access token stored for authorization");
            }

            // Use the resourceURI from the authorization (this contains the subscription data)
            String url = authorization.getResourceURI();

            String cacheKey = retailCustomerId + "|" + accessToken + "|" + url;
            CachedXml cached = cache.get(cacheKey);
            if (cached != null && (System.currentTimeMillis() - cached.at) < cacheTtlMs) {
                HttpHeaders respHeaders = new HttpHeaders();
                if (cached.contentType != null) respHeaders.setContentType(cached.contentType);
                respHeaders.set("X-TP-CACHE", "HIT");
                return ResponseEntity.ok().headers(respHeaders).body(cached.body);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.set("Accept", "application/atom+xml, application/xml;q=0.9, */*;q=0.1");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);

            String body = resp.getBody();

            // PetSafe FE expects IntervalReadings inside IntervalBlocks. Many DC subscription feeds only include
            // IntervalBlock links, not the IntervalBlock content. In that case, follow the related IntervalBlock
            // links and aggregate them into one feed.
            if (!containsIntervalBlocks(body) && body != null) {
                Set<String> intervalBlockUrls = new LinkedHashSet<>();
                Matcher m = INTERVAL_BLOCK_LINK.matcher(body);
                while (m.find()) {
                    String url1 = m.group(1);
                    String url2 = m.group(2);
                    String ibUrl = url1 != null ? url1 : url2;
                    if (ibUrl != null) {
                        intervalBlockUrls.add(ibUrl);
                    }
                }

                if (!intervalBlockUrls.isEmpty()) {
                    List<String> intervalBlockFeeds = new ArrayList<>();
                    int fetched = 0;
                    for (String ibUrl : intervalBlockUrls) {
                        if (maxIntervalBlockFeeds > 0 && fetched >= maxIntervalBlockFeeds) {
                            break;
                        }
                        try {
                            ResponseEntity<String> ibResp = restTemplate.exchange(ibUrl, HttpMethod.GET, entity, String.class);
                            intervalBlockFeeds.add(ibResp.getBody());
                            fetched++;
                        } catch (Exception e) {
                            logger.warn("Failed to fetch IntervalBlock feed from {}", ibUrl, e);
                        }
                    }
                    if (!intervalBlockFeeds.isEmpty()) {
                        body = buildAggregateIntervalBlockFeed(intervalBlockFeeds, privacyLevel);
                    }
                }
            }

            cache.put(cacheKey, new CachedXml(System.currentTimeMillis(), body, resp.getHeaders().getContentType()));

            // Save XML to file in TP (optional)
            if (persistXml) {
                try {
                    java.nio.file.Path path = java.nio.file.Paths.get("/data/xml/" + retailCustomerId + "_" + System.currentTimeMillis() + ".xml");
                    java.nio.file.Files.createDirectories(path.getParent());
                    if (body != null) {
                        java.nio.file.Files.write(path, body.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    logger.error("Error saving XML to file for retailCustomer {}", retailCustomerId, e);
                }
            }

            HttpHeaders respHeaders = new HttpHeaders();
            respHeaders.setContentType(resp.getHeaders().getContentType());
            respHeaders.set("X-TP-CACHE", "MISS");
            respHeaders.set("X-TP-IB-LIMIT", String.valueOf(maxIntervalBlockFeeds));
            return ResponseEntity.status(resp.getStatusCode()).headers(respHeaders).body(body);
        } catch (Exception ex) {
            logger.error("Error fetching data for retailCustomer {}", retailCustomerId, ex);
            return ResponseEntity.status(500).body("Error fetching data: " + ex.getMessage());
        }
    }
}
