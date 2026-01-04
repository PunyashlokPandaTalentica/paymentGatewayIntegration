package com.paymentgateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI paymentOrchestrationOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Payment Orchestration Service API")
                        .description("Single-tenant payment orchestration service with Authorize.Net integration. " +
                                "Provides REST APIs for order management, payment processing, and transaction handling. " +
                                "All endpoints require OAuth2 Bearer token authentication except for webhooks and public endpoints.")
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Payment Gateway Team")
                                .email("support@paymentgateway.com"))
                        .license(new License()
                                .name("Proprietary")
                                .url("https://paymentgateway.com/license")))
                .servers(List.of(
                        new Server()
                                .url("http://localhost:8080")
                                .description("Local Development Server"),
                        new Server()
                                .url("https://api.paymentgateway.com")
                                .description("Production Server")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearer-jwt", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("OAuth2 JWT Bearer Token. Obtain token from your OAuth2 provider.")))
                .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
    }
}

