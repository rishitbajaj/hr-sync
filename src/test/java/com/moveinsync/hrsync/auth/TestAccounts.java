package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

public final class TestAccounts {

    public static final String PASSWORD = "test-password";

    private static final ConcurrentHashMap<String, String> TOKENS = new ConcurrentHashMap<>();

    private TestAccounts() {
    }

    public static HttpHeaders admin(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            TestRestTemplate restTemplate,
            ObjectMapper objectMapper,
            String tenant
    ) {
        return bearer(jdbc, encoder, restTemplate, objectMapper, "admin-" + tenant, tenant, "ADMIN", null);
    }

    public static HttpHeaders operator(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            TestRestTemplate restTemplate,
            ObjectMapper objectMapper
    ) {
        return bearer(jdbc, encoder, restTemplate, objectMapper, "platform-operator", "platform", "OPERATOR", null);
    }

    public static HttpHeaders employee(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            TestRestTemplate restTemplate,
            ObjectMapper objectMapper,
            String tenant,
            String employeeId
    ) {
        return bearer(
                jdbc,
                encoder,
                restTemplate,
                objectMapper,
                "employee-" + tenant + "-" + employeeId,
                tenant,
                "EMPLOYEE",
                employeeId
        );
    }

    private static HttpHeaders bearer(
            JdbcTemplate jdbc,
            PasswordEncoder encoder,
            TestRestTemplate restTemplate,
            ObjectMapper objectMapper,
            String username,
            String tenant,
            String role,
            String employeeId
    ) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from users where username = ?",
                Integer.class,
                username
        );
        if (existing == null || existing == 0) {
            jdbc.update(
                    """
                            insert into users (id, tenant_id, username, password_hash, role, employee_id)
                            values (?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID(),
                    tenant,
                    username,
                    encoder.encode(PASSWORD),
                    role,
                    employeeId
            );
        }
        String key = System.identityHashCode(jdbc) + "\n" + username;
        String token = TOKENS.computeIfAbsent(key, ignored -> login(restTemplate, objectMapper, username));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private static String login(TestRestTemplate restTemplate, ObjectMapper objectMapper, String username) {
        try {
            ResponseEntity<String> login = restTemplate.postForEntity(
                    "/auth/token",
                    Map.of("username", username, "password", PASSWORD),
                    String.class
            );
            assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
            return objectMapper.readTree(login.getBody()).get("token").asText();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign in " + username);
        }
    }
}
