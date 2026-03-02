package org.greenbuttonalliance.espi.thirdparty.web;

import org.greenbuttonalliance.espi.common.domain.ApplicationInformation;
import org.greenbuttonalliance.espi.common.domain.Authorization;
import org.greenbuttonalliance.espi.common.domain.RetailCustomer;
import org.greenbuttonalliance.espi.common.domain.Routes;
import org.greenbuttonalliance.espi.common.service.AuthorizationService;
import org.greenbuttonalliance.espi.common.service.ApplicationInformationService;
import org.greenbuttonalliance.espi.common.service.RetailCustomerService;
import org.greenbuttonalliance.espi.thirdparty.repository.UsagePointRESTRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.UserDeniedAuthorizationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.persistence.NoResultException;
import javax.xml.bind.JAXBException;
import java.security.Principal;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
public class AuthorizationController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationController.class);

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private ApplicationInformationService applicationInformationService;

    @Autowired
    private RetailCustomerService retailCustomerService;

    @Autowired
    private UsagePointRESTRepository usagePointRESTRepository;

    @Autowired
    @Qualifier("clientRestTemplateFactory")
    private ClientRestTemplateFactory templateFactory;

    @RequestMapping(
        value = { Routes.THIRD_PARTY_OAUTH_CODE_CALLBACK, "/espi/1_1/OAuthCallBack" },
        method = RequestMethod.GET)
    public String authorization(
        @RequestParam(value = "code", required = false) String code,
        @RequestParam(value = "state", required = false) String state,
        ModelMap model,
        Principal principal,
        @RequestParam(value = "error", required = false) String error,
        @RequestParam(value = "error_description", required = false) String error_description,
        @RequestParam(value = "error_uri", required = false) String error_uri) {

        log.info("OAuth-callback reached: code={}, state={}", code, state);

        Authorization authorization = null;
        ApplicationInformation appInfo = null;
        
        try {
            // Try to find existing authorization by state
            try {
                authorization = authorizationService.findByState(state);
                appInfo = authorization.getApplicationInformation();
            } catch (NoResultException | EmptyResultDataAccessException ex) {
                // No existing authorization found, create a new one
                log.info("No existing authorization found for state: {}, creating new one", state);
                
                // Get the third_party application info
                try {
                    appInfo = applicationInformationService.findByClientId("third_party");
                } catch (Exception appEx) {
                    log.error("Could not find third_party application info", appEx);
                    String petsafePort = System.getenv().getOrDefault("VITE_PETSAFE_PORT", "5174");
                    if (!petsafePort.matches("[0-9]{2,5}")) petsafePort = "5174";
                    return "redirect:http://localhost:" + petsafePort + "/?error=app_not_found";
                }
                
                // Create new authorization
                authorization = new Authorization();
                authorization.setState(state);
                authorization.setApplicationInformation(appInfo);
                authorization.setUpdated(new GregorianCalendar());
                authorization.setUUID(java.util.UUID.randomUUID());
                
                // Try to find a retail customer (may not be available in server-to-server call)
                try {
                    RetailCustomer customer = retailCustomerService.findById(1L); // Default to first customer
                    authorization.setRetailCustomer(customer);
                } catch (Exception custEx) {
                    log.warn("Could not find retail customer, continuing without", custEx);
                }
                
                authorizationService.persist(authorization);
            }

            if (code != null) {
                // persist the code and grant type
                authorization.setCode(code);
                authorization.setGrantType("authorization_code");
                authorization.setUpdated(new GregorianCalendar());
                authorizationService.merge(authorization);

                // build form for token request
                MultiValueMap<String,String> form = new LinkedMultiValueMap<>();
                form.add("grant_type",   "authorization_code");
                form.add("code",         code);
                form.add("redirect_uri", appInfo.getRedirectUri());

                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

                HttpEntity<MultiValueMap<String,String>> request =
                    new HttpEntity<>(form, headers);

                String tokenEndpoint = appInfo.getAuthorizationServerTokenEndpoint();
                log.info("About to exchange code for token @ {}", tokenEndpoint);

                RestTemplate restTemplate =
                    templateFactory.newClientRestTemplate(
                        appInfo.getClientId(),
                        appInfo.getClientSecret()
                    );

                ResponseEntity<OAuth2AccessToken> resp = restTemplate.exchange(
                    tokenEndpoint,
                    HttpMethod.POST,
                    request,
                    OAuth2AccessToken.class
                );
                OAuth2AccessToken token = resp.getBody();
                log.info("← token-response: value={}", token != null ? token.getValue() : "null");

                if (token != null && token.getValue() != null) {
                    // populate your Authorization entity
                    authorization.setAccessToken(token.getValue());
                    authorization.setTokenType(token.getTokenType());
                    // *** FIX: cast int → long here ***
                    authorization.setExpiresIn((long) token.getExpiresIn());

                    if (token.getRefreshToken() != null) {
                        authorization.setRefreshToken(token.getRefreshToken().getValue());
                    }

                    authorization.setScope(String.join(" ", token.getScope()));

                    // pick up any extras
                    Map<String,Object> extras = token.getAdditionalInformation();
                    if (extras.containsKey("authorizationURI")) {
                        authorization.setAuthorizationURI((String) extras.get("authorizationURI"));
                    }
                    if (extras.containsKey("resourceURI")) {
                        authorization.setResourceURI((String) extras.get("resourceURI"));
                    }

                    authorization.setStatus("1");
                    authorization.setState(null);
                    authorization.setUpdated(new GregorianCalendar());
                    authorizationService.merge(authorization);

                    // optional: import UsagePoints immediately
                    long customerId = currentCustomer(principal).getId();
                    try {
                        usagePointRESTRepository.findAllByRetailCustomerId(customerId);
                    } catch (JAXBException ignore) { }
                } else {
                    log.error("/oauth/token returned no access_token");
                }

            } else {
                log.error("authorization error: {} – {}", error, error_description);
                authorization.setError(error);
                authorization.setErrorDescription(error_description);
                authorization.setErrorUri(error_uri);
                authorization.setStatus("2");
                authorization.setState(null);
                authorization.setUpdated(new GregorianCalendar());
                authorizationService.merge(authorization);
                throw new UserDeniedAuthorizationException("Error: " + error_description);
            }

        } catch (NoResultException | EmptyResultDataAccessException ex) {
            return "/home";  // no matching state → back to home

        } catch (HttpClientErrorException ex) {
            log.error("token exchange failed", ex);
            throw new UserDeniedAuthorizationException("Unable to retrieve OAuth token", ex);
        }

        // redirect to the PetSafe FE connected data page
        String petsafePort = System.getenv().getOrDefault("VITE_PETSAFE_PORT", "5174");
        if (!petsafePort.matches("[0-9]{2,5}")) petsafePort = "5174";
        return "redirect:http://localhost:" + petsafePort + "/?success=true&state=" + state + "&uuid=" + authorization.getUUID();
    }

    @RequestMapping(value = "/api/auth/getToken", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getToken(@RequestParam String uuid) {
        try {
            Authorization auth = authorizationService.findByUUID(UUID.fromString(uuid));
            if (auth == null) {
                log.warn("No authorization found for UUID: {}", uuid);
                return ResponseEntity.notFound().build();
            }
            
            String accessToken = auth.getAccessToken();
            if (accessToken == null) {
                log.warn("No access token found for UUID: {}", uuid);
                return ResponseEntity.status(401).body(Map.of("error", "No access token available"));
            }
            
            log.info("Returning access token for UUID: {}", uuid);
            return ResponseEntity.ok(Map.of("access_token", accessToken));
        } catch (Exception e) {
            log.error("Error retrieving token for UUID: " + uuid, e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    @RequestMapping(value = "/api/auth/currentToken", method = RequestMethod.GET)
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getCurrentToken(Authentication principal) {
        try {
            long customerId = currentCustomer(principal).getId();
            log.info("Fetching current token for customer ID: {}", customerId);
            
            // Get the most recent authorization for this customer
            List<Authorization> authorizations = authorizationService.findAllByRetailCustomerId(customerId);
            if (authorizations == null || authorizations.isEmpty()) {
                log.warn("No authorizations found for customer ID: {}", customerId);
                return ResponseEntity.status(404).body(Map.of("error", "No authorizations found"));
            }
            
            // Find the most recent authorization with an access token
            Authorization latestAuth = authorizations.stream()
                .filter(auth -> auth.getAccessToken() != null)
                .max((a1, a2) -> a1.getPublished().compareTo(a2.getPublished()))
                .orElse(null);
                
            if (latestAuth == null || latestAuth.getAccessToken() == null) {
                log.warn("No valid access token found for customer ID: {}", customerId);
                return ResponseEntity.status(404).body(Map.of("error", "No valid access token found"));
            }
            
            log.info("Returning current access token for customer ID: {}, token: {}", customerId, latestAuth.getAccessToken());
            return ResponseEntity.ok(Map.of(
                "access_token", latestAuth.getAccessToken(),
                "authorization_id", latestAuth.getId(),
                "published", latestAuth.getPublished()
            ));
        } catch (Exception e) {
            log.error("Error retrieving current token", e);
            return ResponseEntity.status(500).body(Map.of("error", "Internal server error"));
        }
    }

    @RequestMapping(value = Routes.THIRD_PARTY_AUTHORIZATION, method = RequestMethod.GET)
    public String index(ModelMap model, Authentication principal) {
        long customerId = currentCustomer(principal).getId();
        model.put("authorizationList",
            authorizationService.findAllByRetailCustomerId(customerId));
        return "/RetailCustomer/AuthorizationList/index";
    }
}
