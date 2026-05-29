package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

@Configuration
public class CorsWebFluxConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowCredentials(true);
        c.addAllowedOriginPattern("http://localhost:*");
        c.addAllowedOriginPattern("http://127.0.0.1:*");
        c.addAllowedHeader(CorsConfiguration.ALL);
        c.addAllowedMethod(CorsConfiguration.ALL);
        c.addExposedHeader("Content-Type");

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", c);
        return new CorsWebFilter(source);
    }
}
