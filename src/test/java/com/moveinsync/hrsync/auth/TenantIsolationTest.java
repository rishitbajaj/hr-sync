package com.moveinsync.hrsync.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class TenantIsolationTest {

    private static final String SECRET = "Secret Name";

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
    void anAdminCannotWriteAnotherTenantsData() throws Exception {
        HttpHeaders admin = json(TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "tenant-left"));
        String event = """
                {"events":[{"tenantId":"tenant-right","employeeId":"emp-1","sourceVersion":1,"type":"CREATE","fields":{"name":"%s","email":"secret@leak.example","phone":"+15550000001","officeCode":"BLR"}}]}
                """.formatted(SECRET);

        ResponseEntity<String> ingest = restTemplate.postForEntity("/sync/events", new HttpEntity<>(event, admin), String.class);
        assertThat(ingest.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ingest.getBody()).doesNotContain(SECRET).doesNotContain("secret@leak.example");

        ResponseEntity<String> transport = restTemplate.postForEntity(
                "/sync/transport-events",
                new HttpEntity<>(event, admin),
                String.class
        );
        assertThat(transport.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(count("employee", "tenant-right")).isZero();
        assertThat(count("outbox", "tenant-right")).isZero();

        HttpHeaders csv = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "tenant-left");
        csv.setContentType(MediaType.parseMediaType("text/csv"));
        String export = """
                tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift
                tenant-right,emp-1,1,%s,secret@leak.example,+15550000001,M1,BLR,DAY
                """.formatted(SECRET);
        ResponseEntity<String> started = restTemplate.postForEntity(
                "/reconciliation/runs?tenantId=tenant-right",
                new HttpEntity<>(export, csv),
                String.class
        );
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(started.getBody()).doesNotContain(SECRET).doesNotContain("secret@leak.example");
        assertThat(count("import_run", "tenant-right")).isZero();
        assertThat(count("employee", "tenant-right")).isZero();
    }

    @Test
    void aCrossTenantRunLookupIsNotFoundAndOutboxReplayDoesNotCrossTenants() throws Exception {
        HttpHeaders adminB = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "tenant-b");
        adminB.setContentType(MediaType.parseMediaType("text/csv"));
        String export = """
                tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift
                tenant-b,emp-1,1,Pat,pat@example.com,+15550000001,M1,BLR,DAY
                """;
        ResponseEntity<String> started = restTemplate.postForEntity(
                "/reconciliation/runs?tenantId=tenant-b",
                new HttpEntity<>(export, adminB),
                String.class
        );
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        String runId = objectMapper.readTree(started.getBody()).get("id").asText();

        HttpHeaders adminA = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, "tenant-a");
        ResponseEntity<String> hidden = restTemplate.exchange(
                "/reconciliation/runs/" + runId,
                HttpMethod.GET,
                new HttpEntity<>(adminA),
                String.class
        );
        assertThat(hidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(hidden.getBody()).doesNotContain("Pat").doesNotContain("tenant-b");

        HttpHeaders operator = TestAccounts.operator(jdbc, passwordEncoder, restTemplate, objectMapper);
        ResponseEntity<String> named = restTemplate.exchange(
                "/reconciliation/runs/" + runId + "?tenantId=tenant-b",
                HttpMethod.GET,
                new HttpEntity<>(operator),
                String.class
        );
        assertThat(named.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> otherName = restTemplate.exchange(
                "/reconciliation/runs/" + runId + "?tenantId=tenant-a",
                HttpMethod.GET,
                new HttpEntity<>(operator),
                String.class
        );
        assertThat(otherName.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Timestamp now = Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"));
        Long eventId = jdbc.queryForObject(
                """
                        insert into outbox (tenant_id, aggregate_id, event_type, payload, created_at, next_attempt_at, status, attempts)
                        values ('tenant-b', 'emp-1', 'UPDATE', '{"employeeId": "emp-1"}', ?, ?, 'FAILED', 1)
                        returning id
                        """,
                Long.class,
                now,
                now
        );
        ResponseEntity<String> wrongTenant = restTemplate.exchange(
                "/outbox/" + eventId + "/replay?tenantId=tenant-a",
                HttpMethod.POST,
                new HttpEntity<>(operator),
                String.class
        );
        assertThat(wrongTenant.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                "select status from outbox where id = ?",
                String.class,
                eventId
        )).isEqualTo("FAILED");
    }

    private HttpHeaders json(HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private long count(String table, String tenant) {
        Long count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = ?",
                Long.class,
                tenant
        );
        return count == null ? 0 : count;
    }
}
