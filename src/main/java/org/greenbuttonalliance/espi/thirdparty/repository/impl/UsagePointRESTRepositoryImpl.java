/*
 *    Copyright (c) 2018-2021 Green Button Alliance, Inc.
 *
 *    Portions (c) 2013-2018 EnergyOS.org
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 */

package org.greenbuttonalliance.espi.thirdparty.repository.impl;

import org.greenbuttonalliance.espi.common.domain.Authorization;
import org.greenbuttonalliance.espi.common.domain.UsagePoint;
import org.greenbuttonalliance.espi.common.service.AuthorizationService;
import org.greenbuttonalliance.espi.common.service.ImportService;
import org.greenbuttonalliance.espi.common.service.RetailCustomerService;
import org.greenbuttonalliance.espi.common.service.UsagePointService;
import org.greenbuttonalliance.espi.common.repositories.UsagePointRepository;
import org.greenbuttonalliance.espi.thirdparty.repository.UsagePointRESTRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import javax.xml.bind.JAXBException;
    import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.List;

@Repository
public class UsagePointRESTRepositoryImpl implements UsagePointRESTRepository {
    private static final Logger log = LoggerFactory.getLogger(UsagePointRESTRepositoryImpl.class);

    @Autowired
    @Qualifier("restTemplate")
    private RestTemplate template;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private ImportService importService;

    @Autowired
    private UsagePointService usagePointService;

    @Autowired
    private UsagePointRepository usagePointRepository;

    @Autowired
    private RetailCustomerService retailCustomerService;

    @Override
    public List<UsagePoint> findAllByRetailCustomerId(Long retailCustomerId) throws JAXBException {
        Authorization auth = findLatestAuthorization(retailCustomerId);

        if (auth != null && auth.getAccessToken() != null) {
            byte[] xml;
            try {
                xml = fetchUsagePointXml(auth);
            } catch (IOException e) {
                throw new JAXBException("I/O error fetching UsagePoint XML", e);
            }

            if (xml != null) {
                try {
                    // Import the UsagePoint entry directly for this customer
                    importService.importData(new ByteArrayInputStream(xml), retailCustomerId);
                } catch (Exception e) {
                    throw new JAXBException("Error importing UsagePoint XML", e);
                }
            }
        } else {
            log.warn("No OAuth token available for customer {}", retailCustomerId);
        }

        // always return whatever is in the DB now
        return usagePointService.findAllByRetailCustomer(
                retailCustomerService.findById(retailCustomerId));
    }

    @Override
    public UsagePoint findByHashedId(Long retailCustomerId, String usagePointHashedId)
            throws JAXBException {
        for (UsagePoint up : findAllByRetailCustomerId(retailCustomerId)) {
            if (up.getHashedId().equalsIgnoreCase(usagePointHashedId)) {
                return up;
            }
        }
        return null;
    }

    private Authorization findLatestAuthorization(Long customerId) {
        List<Authorization> list = authorizationService.findAllByRetailCustomerId(customerId);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    /**
     * Fetches a single UsagePoint entry by converting
     * /Batch/Subscription/{id} to /Subscription/{id}/UsagePoint and
     * requesting an Atom entry representation.
     */
    private byte[] fetchUsagePointXml(Authorization auth) throws IOException {
        String resourceUri = auth.getResourceURI();
        // e.g. http://host/espi/1_1/resource/Batch/Subscription/5
        String url;
        if (resourceUri.contains("/Batch/Subscription/")) {
            String id = resourceUri.substring(resourceUri.lastIndexOf('/') + 1);
            url = resourceUri.replace("/Batch/Subscription/" + id, "/Subscription/" + id + "/UsagePoint");
        } else {
            // fallback: assume resourceURI points to the subscription itself
            url = resourceUri + "/UsagePoint";
        }
        log.info("GET UsagePoints from [{}]", url);

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + auth.getAccessToken());
        // Request a single Atom entry
        headers.setAccept(Collections.singletonList(
            MediaType.parseMediaType("application/atom+xml;type=entry")
        ));

        HttpEntity<Void> req = new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> resp = template.exchange(
                    url, HttpMethod.GET, req, byte[].class);
            return resp.getBody();
        } catch (HttpClientErrorException e) {
            log.error("Error fetching UsagePoints: {} {}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.FORBIDDEN) {
                // expire token so user must re-authorize
                auth.setAccessToken(null);
                auth.setStatus("2");
                auth.setUpdated(new GregorianCalendar());
                authorizationService.merge(auth);
            }
            return null;
        } catch (RestClientException e) {
            log.error("Unexpected REST error fetching UsagePoints", e);
            return null;
        }
    }
}
