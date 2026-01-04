package com.paymentgateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Security configuration for OAuth2 Resource Server.
 * Configures JWT token validation and security headers.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}")
    private String issuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}")
    private String jwkSetUri;

    @Value("${app.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${app.security.public-endpoints:/v1/webhooks/**,/actuator/health,/swagger-ui/**,/v3/api-docs/**}")
    private String[] publicEndpoints;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        if (!securityEnabled) {
            http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(publicEndpoints).permitAll()
                .requestMatchers("/v1/**").authenticated()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.decoder(jwtDecoder()))
            )
            .headers(headers -> headers
                .contentTypeOptions(contentTypeOptions -> {})
                .frameOptions(frameOptions -> frameOptions.deny())
                .xssProtection(xssProtection -> {})
                .referrerPolicy(referrerPolicy -> referrerPolicy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .httpStrictTransportSecurity(hsts -> hsts
                    .maxAgeInSeconds(31536000)
                )
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource()));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        if (!securityEnabled) {
            // Return a no-op decoder when security is disabled
            return token -> {
                throw new UnsupportedOperationException("JWT validation is disabled");
            };
        }
        
        if (jwkSetUri != null && !jwkSetUri.isEmpty()) {
            return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        } else if (issuerUri != null && !issuerUri.isEmpty()) {
            return NimbusJwtDecoder.withIssuerLocation(issuerUri).build();
        } else {
            // For testing/development when security is enabled but OAuth2 is not configured
            // Return a no-op decoder that will reject all tokens
            return token -> {
                throw new IllegalStateException("OAuth2 JWT configuration is missing. Please configure either jwk-set-uri or issuer-uri, or set app.security.enabled=false");
            };
        }
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*")); // Configure appropriately for production
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

