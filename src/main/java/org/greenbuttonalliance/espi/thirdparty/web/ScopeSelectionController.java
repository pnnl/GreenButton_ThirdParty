package org.greenbuttonalliance.espi.thirdparty.web;

import org.greenbuttonalliance.espi.common.domain.Authorization;
import org.greenbuttonalliance.espi.common.domain.ApplicationInformation;
import org.greenbuttonalliance.espi.common.domain.RetailCustomer;
import org.greenbuttonalliance.espi.common.service.ApplicationInformationService;
import org.greenbuttonalliance.espi.common.service.AuthorizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;

import javax.xml.bind.JAXBException;
import java.security.Principal;
import java.util.UUID;

@Controller
@PreAuthorize("hasRole('ROLE_USER')")
public class ScopeSelectionController extends BaseController {

    private final ApplicationInformationService applicationInformationService;
    private final AuthorizationService            authorizationService;

    @Autowired
    public ScopeSelectionController(
            ApplicationInformationService applicationInformationService,
            AuthorizationService authorizationService) {
        this.applicationInformationService = applicationInformationService;
        this.authorizationService          = authorizationService;
    }

    /**
     * Called when the user picks a Data Custodian.
     * Builds and returns a 302 redirect to
     *    {dcBase}/oauth/authorize?response_type=code&client_id=…&redirect_uri=…&scope=…&state=…
     */
    @RequestMapping(
        value  = "/RetailCustomer/{retailCustomerId}/ScopeSelection",
        method = RequestMethod.POST
    )
    public String handleScopeSelection(
            @PathVariable("retailCustomerId") Long retailCustomerId,
            @RequestParam("Data_custodian")    String dataCustodianId,
            Principal principal
    ) throws JAXBException {

        // 1) Fetch the DC’s OAuth metadata
        ApplicationInformation appInfo =
            applicationInformationService.findByDataCustodianClientId(dataCustodianId);

        // 2) Generate a CSRF‑style state token
        String state = UUID.randomUUID().toString();

        // 3) Persist an Authorization record so callback can look it up
        Authorization auth = new Authorization();
        auth.setUuid(UUID.randomUUID().toString());
        auth.setState( state );
        auth.setStatus("0");  // pending
        auth.setApplicationInformation(appInfo);
        RetailCustomer rc = currentCustomer(principal);
        auth.setRetailCustomer(rc);
        authorizationService.persist(auth);

        // 4) Build redirect URL to the DC’s /oauth/authorize
        String authorizeUrl = UriComponentsBuilder
            .fromHttpUrl(appInfo.getAuthorizationServerAuthorizationEndpoint())
            .queryParam("response_type", "code")
            .queryParam("client_id",       appInfo.getClientId())
            .queryParam("redirect_uri",    appInfo.getRedirectUri())
            .queryParam("scope",           String.join(" ", appInfo.getScope()))
            .queryParam("state",           state)
            .build()
            .toUriString();

        // 5) Trigger the OAuth dance
        return "redirect:" + authorizeUrl;
    }
}
