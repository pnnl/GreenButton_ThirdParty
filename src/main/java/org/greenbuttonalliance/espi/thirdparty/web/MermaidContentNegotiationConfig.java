package org.greenbuttonalliance.espi.thirdparty.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurerAdapter;

@Configuration
public class MermaidContentNegotiationConfig extends WebMvcConfigurerAdapter {

    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer configurer) {
        // This codebase runs under legacy servlet container / Spring MVC settings
        // that favor path extensions for content negotiation.
        // Without this, requests to endpoints ending in ".mmd" can trigger 406.
        configurer.mediaType("mmd", MediaType.TEXT_PLAIN);
        configurer.mediaType("dot", MediaType.TEXT_PLAIN);
        configurer.mediaType("svg", MediaType.valueOf("image/svg+xml"));
    }
}
