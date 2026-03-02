package org.greenbuttonalliance.espi.thirdparty.web;



import org.greenbuttonalliance.espi.common.domain.RetailCustomer;
import org.greenbuttonalliance.espi.common.service.RetailCustomerService;
import org.greenbuttonalliance.espi.common.domain.Authorization;
import org.greenbuttonalliance.espi.common.service.AuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.List;

import org.springframework.web.bind.annotation.CrossOrigin;

@RestController
@CrossOrigin(origins = {"http://localhost:80", "http://localhost:443", "http://localhost:5173", "http://localhost:5174"}, allowedHeaders = "*", allowCredentials = "true")
@RequestMapping("/api/auth")
public class AuthenticationRESTController {

    @Autowired
    private RetailCustomerService retailCustomerService;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private RestTemplate restTemplate;

    @Autowired(required = false)
    @Qualifier("authenticationManager")
    private AuthenticationManager authenticationManager;

    private String resolvePort(String envVar, String fallback) {
        String v = System.getenv(envVar);
        if (v == null || !v.matches("[0-9]{2,5}")) return fallback;
        return v;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> payload,
            HttpServletRequest request,
            HttpServletResponse response) {
        String username = payload.get("username");
        String password = payload.get("password");
        if (username == null || password == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("success", false, "error", "Missing username or password"));
        }
        
        try {
            // Perform real Spring Security authentication so the session is established
            if (authenticationManager == null) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Authentication manager not available"));
            }

            UsernamePasswordAuthenticationToken authReq =
                new UsernamePasswordAuthenticationToken(username, password);
            Authentication authResult;
            try {
                authResult = authenticationManager.authenticate(authReq);
            } catch (AuthenticationException ex) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "error", "Invalid credentials"));
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authResult);
            SecurityContextHolder.setContext(context);
            var session = request.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

            // Also provide basic user info for convenience
            RetailCustomer customer = null;
            try {
                var userDetails = retailCustomerService.loadUserByUsername(username);
                customer = (userDetails instanceof RetailCustomer) ? (RetailCustomer) userDetails : null;
            } catch (Exception ignore) { }

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Authentication successful",
                "user", Map.of("username", username, "id", customer != null ? customer.getId() : null)
            ));
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "error", "Authentication failed: " + e.getMessage()));
        }
    }

    @PostMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@RequestBody Map<String, String> authRequest) {
        try {
            String clientId = authRequest.get("client_id");
            String responseType = authRequest.get("response_type");
            
            if (clientId == null || responseType == null) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "client_id and response_type required"));
            }
            
            // Call DataCustodian's authorize API to get the proper Stars consent page URL
            String datacustodianUrl = "http://localhost:8080/DataCustodian/api/auth/authorize";
            
            try {
                RestTemplate restTemplate = new RestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(authRequest, headers);
                
                // Proxy the request to DataCustodian
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    datacustodianUrl, HttpMethod.POST, requestEntity, 
                    new ParameterizedTypeReference<Map<String, Object>>(){});
                
                // Return the response from DataCustodian (should contain authorization_url to Stars)
                return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
                
            } catch (Exception proxyError) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to proxy to DataCustodian: " + proxyError.getMessage()));
            }
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Authorization failed: " + e.getMessage()));
        }
    }

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(@RequestBody Map<String, String> tokenRequest) {
        try {
            // Call DataCustodian's token API
            String datacustodianUrl = "http://localhost:8080/DataCustodian/api/auth/token";
            
            try {
                RestTemplate restTemplate = new RestTemplate();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                
                HttpEntity<Map<String, String>> requestEntity = new HttpEntity<>(tokenRequest, headers);
                
                // Proxy the request to DataCustodian
                ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    datacustodianUrl, HttpMethod.POST, requestEntity, 
                    new ParameterizedTypeReference<Map<String, Object>>(){});
                
                // Return the response from DataCustodian (should contain access_token)
                return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
                
            } catch (Exception proxyError) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to proxy to DataCustodian: " + proxyError.getMessage()));
            }
            
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Token exchange failed: " + e.getMessage()));
        }
    }

    /**
     * Initiate headless OAuth flow: generates cryptographically strong state, stores
     * it in the user's HttpSession for later validation, and echoes back defaults
     * so the frontend (PetSafe) can redirect the browser to the Stars consent page
     * with only FE->FE navigation. No network call to DataCustodian occurs here.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String,Object>> initiate(@RequestBody(required = false) Map<String,String> body,
                                                       HttpServletRequest request) {
        try {
            if (body == null) body = Map.of();
            String clientId = body.getOrDefault("client_id", "third_party");
            // Determine PetSafe FE port (safe mode aware) for redirect_uri default
            String petSafePort = System.getenv().getOrDefault("VITE_PETSAFE_PORT", "443");
            if (!petSafePort.matches("[0-9]{2,5}")) {
                petSafePort = "443"; // fallback
            }
            String defaultRedirect = "http://localhost:" + petSafePort + "/";
            String redirectUri = body.getOrDefault("redirect_uri", defaultRedirect);
            String scope = body.getOrDefault("scope", "UsagePoint IntervalBlock ElectricPowerUsageSummary");
            // Generate 128-bit random state base64url
            java.security.SecureRandom rng = new java.security.SecureRandom();
            byte[] bytes = new byte[16];
            rng.nextBytes(bytes);
            String state = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            request.getSession(true).setAttribute("OAUTH_STATE", state);
            return ResponseEntity.ok(Map.of(
                    "client_id", clientId,
                    "redirect_uri", redirectUri,
                    "scope", scope,
                    "state", state
            ));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error","Initiate failed: "+ex.getMessage()));
        }
    }

    /** Simple session check for PetSafe UI to reflect real login state. */
    @GetMapping("/session")
    public ResponseEntity<Map<String,Object>> session() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        return ResponseEntity.ok(Map.of("authenticated", authenticated));
    }

    /** Logout: clear Spring Security context and invalidate session. */
    @PostMapping("/logout")
    public ResponseEntity<Map<String,Object>> logout(HttpServletRequest request) {
        try {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false));
        }
    }

    @GetMapping("/data")
    public ResponseEntity<String> getData() {
        try {
            // Get current user
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Not authenticated");
            }
            RetailCustomer customer = (RetailCustomer) auth.getPrincipal();
            long customerId = customer.getId();

            // Get the latest authorization
            List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(customerId);
            if (authorizations.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No authorization found");
            }
            Authorization authorization = authorizations.get(0); // Get the first one
            String accessToken = authorization.getAccessToken();
            if (accessToken == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body("No access token");
            }

            // Fetch data from DataCustodian
            String dataUrl = "http://localhost:8080/DataCustodian/espi/1_1/resource/RetailCustomer/" + customerId + "/UsagePoint";
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<String> entity = new HttpEntity<>(headers);
            ResponseEntity<String> response = restTemplate.exchange(dataUrl, HttpMethod.GET, entity, String.class);

            return ResponseEntity.ok(response.getBody());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error fetching data: " + e.getMessage());
        }
    }

    /**
     * Headless token exchange (browserless). Frontend supplies code & original state; this
     * server calls DataCustodian oauth/token and (optionally) persists Authorization.
     */
    @PostMapping("/exchange")
    public ResponseEntity<Map<String,Object>> exchange(@RequestBody Map<String,String> body, HttpServletRequest request) {
        try {
            String code = body.get("code");
            String grantType = body.getOrDefault("grant_type", "authorization_code");
            String redirectUri = body.get("redirect_uri");
            String clientId = body.get("client_id");
            String clientSecret = body.get("client_secret");
            String returnedState = body.get("state");
            // Validate stored state
            Object sessionStateObj = request.getSession(false) != null ? request.getSession(false).getAttribute("OAUTH_STATE") : null;
            String sessionState = sessionStateObj instanceof String ? (String) sessionStateObj : null;
            if (sessionState == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","No stored state (session expired)"));
            }
            if (returnedState == null || !sessionState.equals(returnedState)) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error","State mismatch"));
            }
            // Clear state after single use
            request.getSession(false).removeAttribute("OAUTH_STATE");
            if (code == null || redirectUri == null || clientId == null) {
                return ResponseEntity.badRequest().body(Map.of("error","code, redirect_uri, client_id required"));
            }
            String tokenUrl = "http://localhost:8080/DataCustodian/oauth/token";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
            if (clientSecret != null) {
                String basic = java.util.Base64.getEncoder().encodeToString((clientId+":"+clientSecret).getBytes());
                headers.add("Authorization", "Basic "+basic);
            }
            org.springframework.util.LinkedMultiValueMap<String,String> params = new org.springframework.util.LinkedMultiValueMap<>();
            params.add("grant_type", grantType);
            params.add("code", code);
            params.add("redirect_uri", redirectUri);
            params.add("client_id", clientId);
            HttpEntity<org.springframework.util.MultiValueMap<String,String>> entity = new HttpEntity<>(params, headers);
            @SuppressWarnings("unchecked")
            ResponseEntity<Map<String,Object>> resp = restTemplate.exchange(tokenUrl, HttpMethod.POST, entity, (Class<Map<String,Object>>)(Class<?>)Map.class);
            Map<String,Object> tokenBody = resp.getBody();
            if (resp.getStatusCode().is2xxSuccessful() && tokenBody != null && tokenBody.get("access_token") != null) {
                try {
                    // Persist minimal authorization record
                    Authorization authorization = new Authorization();
                    authorization.setAccessToken((String) tokenBody.get("access_token"));
                    if (tokenBody.get("refresh_token") instanceof String) {
                        authorization.setRefreshToken((String) tokenBody.get("refresh_token"));
                    }
                    if (tokenBody.get("expires_in") instanceof Number) {
                        authorization.setExpiresIn(((Number) tokenBody.get("expires_in")).longValue());
                    }
                    authorization.setGrantType(grantType);
                    authorization.setCode(code);
                    authorization.setState(returnedState);
                    String scopesFromToken = tokenBody.get("scope") instanceof String ? (String) tokenBody.get("scope") : null;
                    authorization.setScope(scopesFromToken != null ? scopesFromToken : body.getOrDefault("scope", ""));
                    // Link to current authenticated user if present
                    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof RetailCustomer) {
                        RetailCustomer customer = (RetailCustomer) auth.getPrincipal();
                        authorization.setRetailCustomer(customer);
                    }
                    // Ensure non-null resourceURI so findAllByRetailCustomerId can locate it
                    authorization.setResourceURI("urn:auth:" + authorization.getUUID());
                    authorizationService.persist(authorization);
                } catch (Exception persistEx) {
                    // Log persist error but still return token
                }
            }
            return ResponseEntity.status(resp.getStatusCode()).body(tokenBody);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error","Exchange failed: "+ex.getMessage()));
        }
    }


}
