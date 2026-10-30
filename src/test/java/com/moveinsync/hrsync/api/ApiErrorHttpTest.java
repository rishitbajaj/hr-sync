package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.observability.CorrelationIds;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ApiErrorHttpTest {

    private static final String SECRET = "secret@leak.example";
    private static final String TRACE = "trace-http-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void eachErrorClassUsesTheSameBody() throws Exception {
        assertError(exchange(HttpMethod.POST, "/auth/token", json("{\"username\":\"" + SECRET), null),
                HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is not valid JSON");

        String tooLong = "a".repeat(200) + SECRET;
        assertError(exchange(HttpMethod.POST, "/auth/token", json("{\"username\":\"" + tooLong + "\",\"password\":\"x\"}"), null),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed");

        HttpHeaders text = new HttpHeaders();
        text.setContentType(MediaType.TEXT_PLAIN);
        text.set(CorrelationIds.HEADER, TRACE);
        assertError(restTemplate.exchange("/auth/token", HttpMethod.POST, new HttpEntity<>(SECRET, text), String.class),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Unsupported media type");

        assertError(exchange(HttpMethod.GET, "/auth/token", null, null),
                HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed");

        assertError(exchange(HttpMethod.GET, "/no-such-route", null, null),
                HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found");

        assertError(exchange(HttpMethod.GET, "/employees/emp-missing", null, null),
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required");

        HttpHeaders admin = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "err-co");
        assertError(exchange(HttpMethod.GET, "/actuator/prometheus", null, admin),
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden");

        HttpHeaders operator = TestAccounts.operator(jdbc, passwordEncoder, restTemplate, objectMapper);
        Long pending = jdbc.queryForObject(
                """
                        insert into outbox (tenant_id, aggregate_id, event_type, payload, created_at, status, next_attempt_at)
                        values ('err-co', 'emp-err', 'EMPLOYEE_DEACTIVATED', '{}'::jsonb, clock_timestamp(), 'PENDING', clock_timestamp())
                        returning id
                        """,
                Long.class
        );
        assertError(
                exchange(HttpMethod.POST, "/outbox/" + pending + "/discard?tenantId=err-co",
                        json("{\"reason\":\"" + SECRET + "\"}"), operator),
                HttpStatus.CONFLICT, "CONFLICT", "Only a failed event can be changed"
        );

        HttpHeaders ingest = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "err-co");
        ingest.setContentType(MediaType.APPLICATION_JSON);
        ingest.set(CorrelationIds.HEADER, TRACE);
        String huge = "{\"events\":[]}" + "x".repeat(1_100_000);
        assertError(restTemplate.exchange("/sync/events", HttpMethod.POST, new HttpEntity<>(huge, ingest), String.class),
                HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE", "Request body exceeds the maximum of 1048576 bytes");
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, HttpEntity<String> body, HttpHeaders auth) {
        HttpHeaders headers = body == null ? new HttpHeaders() : new HttpHeaders(body.getHeaders());
        if (auth != null) {
            headers.setBearerAuth(auth.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length()));
        }
        headers.set(CorrelationIds.HEADER, TRACE);
        String payload = body == null ? null : body.getBody();
        return restTemplate.exchange(path, method, new HttpEntity<>(payload, headers), String.class);
    }

    private HttpEntity<String> json(String payload) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(payload, headers);
    }

    private void assertError(ResponseEntity<String> response, HttpStatus status, String code, String message) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo(TRACE);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "message", "correlationId");
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).isEqualTo(message);
        assertThat(body.get("correlationId").asText()).isEqualTo(TRACE);
        assertThat(response.getBody()).doesNotContain(SECRET, "Exception", "java.", "select ", "stack");
    }
}
