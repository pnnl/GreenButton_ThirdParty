package org.greenbuttonalliance.espi.thirdparty.web;

import org.apache.http.HttpHost;
import org.apache.http.HttpException;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HttpContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;

public class ClientRestTemplate extends RestTemplate {

    private static final Logger LOG = LoggerFactory.getLogger(ClientRestTemplate.class);

    public ClientRestTemplate() {
        super();
    }

    public ClientRestTemplate(String username, String password) {
        // 1) Set up Apache HttpClient with preemptive basic auth:
        DefaultHttpClient httpClient = new DefaultHttpClient();
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
            new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
            new UsernamePasswordCredentials(username, password)
        );
        httpClient.setCredentialsProvider(credsProvider);
        httpClient.addRequestInterceptor(new PreemptiveAuthInterceptor(), 0);

        // 2) Wire HttpClient into Spring RestTemplate:
        ClientHttpRequestFactory rf =
            new HttpComponentsClientHttpRequestFactory(httpClient);
        this.setRequestFactory(rf);

        // 3) Add converters for form‑encoded POST and JSON:
        this.getMessageConverters().add(new FormHttpMessageConverter());
        this.getMessageConverters().add(new MappingJackson2HttpMessageConverter());

        // 4) Optional: log outbound requests
        this.getInterceptors().add(new ClientHttpRequestInterceptor() {
            @Override
            public ClientHttpResponse intercept(
                    org.springframework.http.HttpRequest req,
                    byte[] body,
                    ClientHttpRequestExecution exec) throws IOException {
                LOG.info("REST → {} {}", req.getMethod(), req.getURI());
                return exec.execute(req, body);
            }
        });
    }

    /**
     * Interceptor to inject BasicScheme into the targetAuthState
     * before the request is executed (preemptive auth).
     */
    static class PreemptiveAuthInterceptor implements org.apache.http.HttpRequestInterceptor {
        @Override
        public void process(org.apache.http.HttpRequest request, HttpContext context)
                throws HttpException, IOException {
            // Wrap the HttpContext in HttpClientContext for easy attribute access:
            HttpClientContext clientContext = HttpClientContext.adapt(context);

            // Get the target AuthState; if no scheme yet, set up BasicScheme + credentials:
            org.apache.http.auth.AuthState authState = clientContext.getTargetAuthState();
            if (authState.getAuthScheme() == null) {
                CredentialsProvider cp = clientContext.getCredentialsProvider();
                HttpHost target = clientContext.getTargetHost();

                Credentials creds = cp.getCredentials(
                    new AuthScope(target.getHostName(), target.getPort())
                );
                if (creds == null) {
                    throw new HttpException("No credentials for preemptive auth");
                }
                authState.setAuthScheme(new BasicScheme());
                authState.setCredentials(creds);
            }
        }
    }
}
