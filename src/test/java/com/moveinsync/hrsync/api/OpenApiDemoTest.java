package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class OpenApiDemoTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void demoProfilePublishesTheSpecAndTheUi() throws Exception {
        ResponseEntity<String> spec = restTemplate.getForEntity("/demo/openapi", String.class);
        assertThat(spec.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode root = objectMapper.readTree(spec.getBody());
        List<String> paths = new ArrayList<>();
        root.at("/paths").fieldNames().forEachRemaining(paths::add);
        assertThat(paths).contains(
                "/health",
                "/auth/token",
                "/sync/events",
                "/sync/transport-events",
                "/employees/{id}",
                "/reconciliation/runs",
                "/reconciliation/runs/{id}",
                "/reconciliation/runs/{id}/approve",
                "/outbox/failed",
                "/outbox/{id}/replay",
                "/outbox/{id}/discard",
                "/demo/trips",
                "/demo/bookings",
                "/demo/failures"
        );
        String text = spec.getBody();
        assertThat(text).contains("RETRYABLE_ERROR").contains("DATABASE_UNAVAILABLE").contains("NOT_PROCESSED");
        assertThat(text).doesNotContain("Ada").doesNotContain("nola").doesNotContain("@leak.");
        assertThat(restTemplate.getForEntity("/demo/swagger-ui", String.class).getStatusCode().is3xxRedirection()
                || restTemplate.getForEntity("/demo/swagger-ui/index.html", String.class).getStatusCode().is2xxSuccessful())
                .isTrue();
        Files.writeString(Path.of("target/openapi.json"), text);
    }
}
