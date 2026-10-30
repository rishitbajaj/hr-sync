package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.AuthSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class RoleMatrixTest {

    private static final String TENANT = "matrix";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String MISSING_RUN = "00000000-0000-0000-0000-000000000001";

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

    private String employee;
    private String admin;
    private String operator;

    @BeforeEach
    void callers() throws Exception {
        insert("emp-self");
        insert("emp-other");
        jdbc.update(
                """
                        insert into booking_svc.trip (tenant_id, trip_id, capacity)
                        values ('matrix', 'trip-matrix', 4)
                        on conflict (tenant_id, trip_id) do nothing
                        """
        );
        employee = bearer(TestAccounts.employee(jdbc, passwordEncoder, restTemplate, objectMapper, TENANT, "emp-self"));
        admin = bearer(TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, TENANT));
        operator = bearer(TestAccounts.operator(jdbc, passwordEncoder, restTemplate, objectMapper));
    }

    @ParameterizedTest(name = "{0} {1} as {4} -> {5}")
    @MethodSource("access")
    void everyRoleIsCheckedOnEveryEndpoint(
            String method,
            String path,
            String body,
            String contentType,
            String caller,
            int expected
    ) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        String token = token(caller);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (contentType != null) {
            headers.setContentType(MediaType.parseMediaType(contentType));
        }
        ResponseEntity<String> response = restTemplate.exchange(
                path,
                HttpMethod.valueOf(method),
                new HttpEntity<>(body, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.valueOf(expected));
    }

    @Test
    void aRejectedLoginAndAForbiddenBodyDoNotLeakSecrets(CapturedOutput output) {
        String password = "correct-horse-battery";
        ResponseEntity<String> login = restTemplate.postForEntity(
                "/auth/token",
                java.util.Map.of("username", "employee-matrix-emp-self", "password", password),
                String.class
        );
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(login.getBody()).doesNotContain(password).doesNotContain(employee);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(employee);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String secretName = "Secret Name";
        String email = "secret@leak.example";
        String phone = "+15550000099";
        ResponseEntity<String> forbidden = restTemplate.postForEntity(
                "/sync/events",
                new HttpEntity<>("""
                        {"events":[{"tenantId":"matrix","employeeId":"emp-self","sourceVersion":1,"type":"UPDATE","fields":{"name":"%s","email":"%s","phone":"%s"}}]}
                        """.formatted(secretName, email, phone), headers),
                String.class
        );
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(forbidden.getBody()).doesNotContain(password).doesNotContain(employee)
                .doesNotContain(secretName).doesNotContain(email).doesNotContain(phone);
        assertThat(output).doesNotContain(password).doesNotContain(employee)
                .doesNotContain(secretName).doesNotContain(email).doesNotContain(phone);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> access() {
        return Stream.of(
                call("GET", "/health", null, null, "NONE", 200),
                call("GET", "/health", null, null, "EMPLOYEE", 200),
                call("GET", "/health", null, null, "ADMIN", 200),
                call("GET", "/health", null, null, "OPERATOR", 200),
                call("GET", "/health", null, null, "EXPIRED", 401),
                call("GET", "/health", null, null, "TAMPERED", 401),
                call("GET", "/health", null, null, "ALG_NONE", 401),
                call("GET", "/health", null, null, "UNKNOWN_ROLE", 401),

                call("GET", "/actuator/health", null, null, "NONE", 401),
                call("GET", "/actuator/health", null, null, "EMPLOYEE", 403),
                call("GET", "/actuator/health", null, null, "ADMIN", 403),
                call("GET", "/actuator/health", null, null, "OPERATOR", 200),
                call("GET", "/actuator/health", null, null, "EXPIRED", 401),

                call("POST", "/sync/events", "{\"events\":[]}", "application/json", "NONE", 401),
                call("POST", "/sync/events", "{\"events\":[]}", "application/json", "EMPLOYEE", 403),
                call("POST", "/sync/events", "{\"events\":[]}", "application/json", "ADMIN", 200),
                call("POST", "/sync/events", "{\"events\":[]}", "application/json", "OPERATOR", 403),
                call("POST", "/sync/events", "{\"events\":[]}", "application/json", "TAMPERED", 401),

                call("POST", "/sync/transport-events", "{\"events\":[]}", "application/json", "NONE", 401),
                call("POST", "/sync/transport-events", "{\"events\":[]}", "application/json", "EMPLOYEE", 403),
                call("POST", "/sync/transport-events", "{\"events\":[]}", "application/json", "ADMIN", 200),
                call("POST", "/sync/transport-events", "{\"events\":[]}", "application/json", "OPERATOR", 403),
                call("POST", "/sync/transport-events", "{\"events\":[]}", "application/json", "ALG_NONE", 401),

                call("GET", "/employees/emp-self", null, null, "NONE", 401),
                call("GET", "/employees/emp-self", null, null, "EMPLOYEE", 200),
                call("GET", "/employees/emp-self", null, null, "ADMIN", 200),
                call("GET", "/employees/emp-self", null, null, "OPERATOR", 400),
                call("GET", "/employees/emp-self?tenantId=matrix", null, null, "OPERATOR", 200),
                call("GET", "/employees/emp-other", null, null, "EMPLOYEE", 404),
                call("GET", "/employees/emp-other", null, null, "ADMIN", 200),
                call("GET", "/employees/emp-other?tenantId=other-co", null, null, "ADMIN", 403),
                call("GET", "/employees/emp-self", null, null, "UNKNOWN_ROLE", 401),

                call("POST", "/reconciliation/runs?tenantId=matrix", "tenantId,employeeId\n", "text/csv", "NONE", 401),
                call("POST", "/reconciliation/runs?tenantId=matrix", "tenantId,employeeId\n", "text/csv", "EMPLOYEE", 403),
                call("POST", "/reconciliation/runs?tenantId=matrix", "tenantId,employeeId\n", "text/csv", "OPERATOR", 403),
                call("POST", "/reconciliation/runs?tenantId=other-co", "tenantId,employeeId\n", "text/csv", "ADMIN", 403),

                call("GET", "/reconciliation/runs/" + MISSING_RUN + "?tenantId=matrix", null, null, "NONE", 401),
                call("GET", "/reconciliation/runs/" + MISSING_RUN + "?tenantId=matrix", null, null, "EMPLOYEE", 403),
                call("GET", "/reconciliation/runs/" + MISSING_RUN + "?tenantId=matrix", null, null, "ADMIN", 404),
                call("GET", "/reconciliation/runs/" + MISSING_RUN + "?tenantId=matrix", null, null, "OPERATOR", 404),
                call("GET", "/reconciliation/runs/" + MISSING_RUN, null, null, "OPERATOR", 400),

                call("POST", "/reconciliation/runs/" + MISSING_RUN + "/approve?tenantId=matrix", null, null, "NONE", 401),
                call("POST", "/reconciliation/runs/" + MISSING_RUN + "/approve?tenantId=matrix", null, null, "EMPLOYEE", 403),
                call("POST", "/reconciliation/runs/" + MISSING_RUN + "/approve?tenantId=matrix", null, null, "ADMIN", 403),
                call("POST", "/reconciliation/runs/" + MISSING_RUN + "/approve?tenantId=matrix", null, null, "OPERATOR", 404),
                call("POST", "/reconciliation/runs/" + MISSING_RUN + "/approve?tenantId=matrix", null, null, "EXPIRED", 401),

                call("GET", "/outbox/failed?tenantId=matrix", null, null, "NONE", 401),
                call("GET", "/outbox/failed?tenantId=matrix", null, null, "EMPLOYEE", 403),
                call("GET", "/outbox/failed?tenantId=matrix", null, null, "ADMIN", 403),
                call("GET", "/outbox/failed?tenantId=matrix", null, null, "OPERATOR", 200),
                call("GET", "/outbox/failed?tenantId=matrix", null, null, "TAMPERED", 401),

                call("POST", "/outbox/999999/replay?tenantId=matrix", null, null, "NONE", 401),
                call("POST", "/outbox/999999/replay?tenantId=matrix", null, null, "EMPLOYEE", 403),
                call("POST", "/outbox/999999/replay?tenantId=matrix", null, null, "ADMIN", 403),
                call("POST", "/outbox/999999/replay?tenantId=matrix", null, null, "OPERATOR", 404),

                call("POST", "/outbox/999999/discard?tenantId=matrix", "{\"reason\":\"x\"}", "application/json", "NONE", 401),
                call("POST", "/outbox/999999/discard?tenantId=matrix", "{\"reason\":\"x\"}", "application/json", "EMPLOYEE", 403),
                call("POST", "/outbox/999999/discard?tenantId=matrix", "{\"reason\":\"x\"}", "application/json", "ADMIN", 403),
                call("POST", "/outbox/999999/discard?tenantId=matrix", "{\"reason\":\"x\"}", "application/json", "OPERATOR", 404),

                call("GET", "/demo/bookings", null, null, "NONE", 200),
                call("GET", "/demo/bookings", null, null, "EMPLOYEE", 200),
                call("POST", "/demo/trips", "{\"tenantId\":\"matrix\",\"tripId\":\"trip-matrix\",\"capacity\":4}", "application/json", "NONE", 200),
                call("POST", "/demo/trips", "{\"tenantId\":\"matrix\",\"tripId\":\"trip-matrix\",\"capacity\":4}", "application/json", "ALG_NONE", 401),
                call("POST", "/demo/bookings", "{\"bookingId\":\"book-matrix\",\"tenantId\":\"matrix\",\"employeeId\":\"emp-self\",\"tripId\":\"trip-matrix\",\"startsAt\":\"2026-10-31T00:00:00Z\"}", "application/json", "NONE", 200),
                call("POST", "/demo/failures", "{\"failures\":0,\"timeouts\":0,\"lostResponses\":0}", "application/json", "NONE", 200),
                call("POST", "/demo/failures", "{\"failures\":0,\"timeouts\":0,\"lostResponses\":0}", "application/json", "UNKNOWN_ROLE", 401)
        );
    }

    private static org.junit.jupiter.params.provider.Arguments call(
            String method,
            String path,
            String body,
            String contentType,
            String caller,
            int expected
    ) {
        return org.junit.jupiter.params.provider.Arguments.of(method, path, body, contentType, caller, expected);
    }

    private String token(String caller) throws Exception {
        return switch (caller) {
            case "NONE" -> null;
            case "EMPLOYEE" -> employee;
            case "ADMIN" -> admin;
            case "OPERATOR" -> operator;
            case "EXPIRED" -> tokens.issue(new AuthenticatedCaller("employee-matrix-emp-self", TENANT, UserRole.EMPLOYEE, "emp-self"), Instant.EPOCH);
            case "TAMPERED" -> admin.substring(0, admin.length() - 2) + "aa";
            case "ALG_NONE" -> noneAlgorithm();
            case "UNKNOWN_ROLE" -> signed("{\"sub\":\"employee-matrix-emp-self\",\"tenant\":\"matrix\",\"role\":\"SUPERUSER\",\"exp\":9999999999}");
            default -> throw new IllegalArgumentException(caller);
        };
    }

    private String noneAlgorithm() {
        String header = encode("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = encode("{\"sub\":\"employee-matrix-emp-self\",\"tenant\":\"matrix\",\"role\":\"OPERATOR\",\"exp\":9999999999}");
        return header + "." + payload + ".e30";
    }

    private String signed(String payloadJson) throws Exception {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode(payloadJson);
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(settings.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + new String(ENCODER.encode(mac.doFinal(input.getBytes(StandardCharsets.UTF_8))), StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return new String(ENCODER.encode(value.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    private static String bearer(HttpHeaders headers) {
        return headers.getFirst(HttpHeaders.AUTHORIZATION).substring("Bearer ".length());
    }

    private void insert(String employeeId) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from employee where tenant_id = ? and employee_id = ?",
                Integer.class,
                TENANT,
                employeeId
        );
        if (existing != null && existing > 0) {
            return;
        }
        jdbc.update(
                """
                        insert into employee (
                            id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                            pickup_point, special_assistance, hr_version, transport_version, status, updated_at
                        ) values (?, ?, ?, 'Pat', ?, '+15550000001', 'M1', null, 'DAY', null, null, 1, 0, 'ACTIVE', ?)
                        """,
                java.util.UUID.randomUUID(),
                TENANT,
                employeeId,
                employeeId + "@example.com",
                Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"))
        );
    }
}
