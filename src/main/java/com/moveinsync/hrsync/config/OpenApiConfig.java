package com.moveinsync.hrsync.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("demo")
public class OpenApiConfig {

    public static final String BEARER = "bearer";

    @Bean
    OpenAPI hrSyncOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("hr-sync")
                        .version("0.1.0")
                        .description("""
                                Roster sync. A bearer token is the `token` from POST /auth/token.
                                Ingest outcomes are APPLIED, REJECTED, IGNORED, and RETRYABLE_ERROR.
                                RETRYABLE_ERROR is HTTP 503. The body is the per-record results plus correlationId, and Retry-After is set.
                                GET /health, POST /auth/token, and /demo/** are open. Everything else needs the role named on the operation.
                                GET /actuator/prometheus, GET /actuator/health, and GET /actuator/metrics are operator routes. Their bodies are the actuator formats, not this document's schemas.
                                """))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("HS256 JWT from POST /auth/token")));
    }

    @Bean
    GroupedOpenApi hrSyncApi() {
        return GroupedOpenApi.builder()
                .group("hr-sync")
                .packagesToScan("com.moveinsync.hrsync.api")
                .build();
    }
}
