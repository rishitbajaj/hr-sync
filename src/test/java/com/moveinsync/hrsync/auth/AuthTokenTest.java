package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.AuthSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AuthTokenTest {

    private static final String PASSWORD = "correct-password";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

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

    @Autowired
    private JwtService tokens;

    @Autowired
    private AuthSettings settings;

    @Autowired
    private LoginLockout lockout;

    @Test
    void loginReturnsATokenThatOpensAnOperatorEndpoint(CapturedOutput output) throws Exception {
        insert("operator-auth", "OPERATOR", null);
        ResponseEntity<String> login = login("operator-auth", PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = objectMapper.readTree(login.getBody()).get("token").asText();
        assertThat(login.getBody()).contains("expiresInSeconds").doesNotContain(PASSWORD);
        assertThat(output).doesNotContain(PASSWORD).doesNotContain(token);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> failed = restTemplate.exchange(
                "/outbox/failed?tenantId=acme",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aWrongPasswordAndALockoutUseTheSameRejection(CapturedOutput output) throws Exception {
        insert("locked-user", "OPERATOR", null);
        settings.setMaxLoginFailures(2);
        try {
            ResponseEntity<String> wrong = login("locked-user", "nope");
            assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(wrong.getBody()).doesNotContain("nope").doesNotContain(PASSWORD);
            login("locked-user", "nope");
            ResponseEntity<String> locked = login("locked-user", PASSWORD);
            assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            JsonNode wrongBody = objectMapper.readTree(wrong.getBody());
            JsonNode lockedBody = objectMapper.readTree(locked.getBody());
            assertThat(lockedBody.get("code")).isEqualTo(wrongBody.get("code"));
            assertThat(lockedBody.get("message").asText()).isEqualTo("Invalid username or password");
            assertThat(lockedBody.get("message")).isEqualTo(wrongBody.get("message"));
            assertThat(wrongBody.get("correlationId").asText()).isNotBlank();
            assertThat(lockedBody.get("correlationId").asText())
                    .isNotBlank()
                    .isNotEqualTo(wrongBody.get("correlationId").asText());
            assertThat(output).doesNotContain(PASSWORD).doesNotContain("nope");
        } finally {
            settings.setMaxLoginFailures(5);
            lockout.clear("locked-user");
        }
    }

    @Test
    void tamperedExpiredNoneAndUnknownRoleTokensAreRejected() throws Exception {
        insert("token-user", "OPERATOR", null);
        String token = objectMapper.readTree(login("token-user", PASSWORD).getBody()).get("token").asText();
        String tampered = token.substring(0, token.length() - 2) + "aa";
        assertThat(call(tampered).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        String expired = tokens.issue(
                new AuthenticatedCaller("token-user", "acme", UserRole.OPERATOR, null),
                Instant.EPOCH
        );
        assertThat(call(expired).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(call(noneAlgorithm()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call(signed("{\"sub\":\"token-user\",\"tenant\":\"acme\",\"role\":\"SUPERUSER\",\"exp\":9999999999}"))
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(call("not-a-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<String> call(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/outbox/failed?tenantId=acme", HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> login(String username, String password) {
        return restTemplate.postForEntity(
                "/auth/token",
                Map.of("username", username, "password", password),
                String.class
        );
    }

    private void insert(String username, String role, String employeeId) {
        jdbc.update(
                """
                        insert into users (id, tenant_id, username, password_hash, role, employee_id)
                        values (?, 'acme', ?, ?, ?, ?)
                        """,
                UUID.randomUUID(),
                username,
                passwordEncoder.encode(PASSWORD),
                role,
                employeeId
        );
    }

    private String noneAlgorithm() {
        String header = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = encode("{\"sub\":\"token-user\",\"tenant\":\"acme\",\"role\":\"OPERATOR\",\"exp\":9999999999}");
        return header + "." + payload + ".e30";
    }

    private String signed(String payloadJson) throws Exception {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode(payloadJson);
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(settings.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = new String(ENCODER.encode(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
        return input + "." + signature;
    }

    private static String encode(String value) {
        return new String(ENCODER.encode(value.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }
}
