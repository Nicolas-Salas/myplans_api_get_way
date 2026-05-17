package com.myplans.api_gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * CORS configurado programáticamente para WebFlux. Reemplaza la
 * sección globalcors del application.yml original, que tenía un bug:
 * `allowedOrigins: "http://a, http://b"` se interpreta como UN solo
 * origen literal con coma adentro, no como una lista.
 *
 * Acá leemos los orígenes desde una propiedad y los partimos por
 * coma para que el yml sea cómodo de mantener:
 *
 *   gateway.cors.allowed-origins: "http://localhost:3000, http://localhost:5173"
 */
@Configuration
public class CorsConfig {

    @Value("${gateway.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration cors = new CorsConfiguration();

        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        cors.setAllowedOrigins(origins);
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("*"));
        cors.setExposedHeaders(List.of("Content-Disposition", "Authorization"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return new CorsWebFilter(source);
    }
}
