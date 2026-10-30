package com.moveinsync.hrsync.metrics;

import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.booking.MockBookingService;
import com.moveinsync.hrsync.config.AuthSettings;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.DeactivationEventConsumer;
import com.moveinsync.hrsync.service.OutboxPoller;
import com.moveinsync.hrsync.service.ReconciliationService;
import com.moveinsync.hrsync.service.SyncEventService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class PrometheusMetricsTest {

    private static final String TENANT = "metrics-secret-co";
    private static final String EMPLOYEE = "emp-metrics-secret";
    private static final String NAME = "Metrics Secret Name";
    private static final String EMAIL = "metrics-secret@leak.example";
    private static final String PHONE = "+15551239999";
    private static final String PICKUP = "Secret Pickup Gate";
    private static final String PASSWORD = "metrics-secret-password";
    private static final String OFFICE = "BLR";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private OutboxMetrics outboxMetrics;

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private DeactivationEventConsumer deactivations;

    @Autowired
    private MockBookingService bookings;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @Autowired
    private AuthSettings authSettings;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void resetHooks() {
        deactivations.setAfterCancel(null);
    }

    @Test
    void ingestCountersMoveOnlyForTheMatchingOutcome() {
        saveOffice(TENANT);
        double applied = count("ingest.applied", "reason", "none", "channel", "HR");
        double stale = count("ingest.stale_ignored", "reason", "STALE_IGNORED", "channel", "HR");
        double duplicate = count("ingest.duplicate", "reason", "DUPLICATE", "channel", "HR");
        double rejected = count("ingest.rejected", "reason", "UNKNOWN_OFFICE", "channel", "HR");
        double transport = count("ingest.applied", "reason", "none", "channel", "TRANSPORT");
        long timed = meters.timer("ingest.duration").count();

        syncEvents.ingest(List.of(create(1)));
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 1);
        assertThat(count("ingest.stale_ignored", "reason", "STALE_IGNORED", "channel", "HR")).isEqualTo(stale);
        assertThat(count("ingest.duplicate", "reason", "DUPLICATE", "channel", "HR")).isEqualTo(duplicate);
        assertThat(count("ingest.rejected", "reason", "UNKNOWN_OFFICE", "channel", "HR")).isEqualTo(rejected);
        assertThat(meters.timer("ingest.duration").count()).isEqualTo(timed + 1);

        syncEvents.ingest(List.of(new SyncEvent(
                TENANT, EMPLOYEE, 1L, EventType.UPDATE,
                new EmployeeFields(null, null, null, null, null, PICKUP, null, null)
        )), FieldChannel.TRANSPORT);
        assertThat(count("ingest.applied", "reason", "none", "channel", "TRANSPORT")).isEqualTo(transport + 1);
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 1);

        syncEvents.ingest(List.of(create(1)));
        assertThat(count("ingest.duplicate", "reason", "DUPLICATE", "channel", "HR")).isEqualTo(duplicate + 1);
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 1);
        assertThat(count("ingest.stale_ignored", "reason", "STALE_IGNORED", "channel", "HR")).isEqualTo(stale);

        syncEvents.ingest(List.of(create(3)));
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 2);
        syncEvents.ingest(List.of(create(2)));
        assertThat(count("ingest.stale_ignored", "reason", "STALE_IGNORED", "channel", "HR")).isEqualTo(stale + 1);
        assertThat(count("ingest.duplicate", "reason", "DUPLICATE", "channel", "HR")).isEqualTo(duplicate + 1);
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 2);

        syncEvents.ingest(List.of(new SyncEvent(
                TENANT, "emp-unknown-office", 1L, EventType.CREATE,
                new EmployeeFields(NAME, EMAIL, PHONE, "M1", "NO-SUCH-OFFICE", PICKUP, null, "DAY")
        )));
        assertThat(count("ingest.rejected", "reason", "UNKNOWN_OFFICE", "channel", "HR")).isEqualTo(rejected + 1);
        assertThat(count("ingest.applied", "reason", "none", "channel", "HR")).isEqualTo(applied + 2);

        String scrape = prometheus(operatorHeaders());
        assertThat(scrape).contains("# TYPE ingest_duration_seconds histogram");
        assertThat(scrape).contains("ingest_duration_seconds_bucket{le=\"+Inf\"}");
        assertThat(scrape).doesNotContain(NAME, EMAIL, PHONE, PICKUP, PASSWORD, EMPLOYEE, TENANT);
    }

    @Test
    void reconciliationCountersMoveOnlyForTheFinalStatus() {
        String tenant = "metrics-recon";
        saveOffice(tenant);
        syncEvents.ingest(List.of(new SyncEvent(
                tenant, "emp-recon", 1L, EventType.CREATE,
                new EmployeeFields("Ada", "ada@example.com", "+15550000001", "M1", OFFICE, null, null, "DAY")
        )));
        double completed = count("reconciliation.runs", "status", "COMPLETED");
        double halted = count("reconciliation.runs", "status", "HALTED");
        double failed = count("reconciliation.runs", "status", "FAILED");
        double approved = count("reconciliation.runs", "status", "APPROVED");
        double haltMeter = count("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED");
        long timed = meters.timer("reconciliation.duration").count();

        assertThatThrownBy(() -> reconciliation.run(
                tenant,
                new ByteArrayInputStream("not-a-row\n".getBytes(StandardCharsets.UTF_8))
        )).isInstanceOf(RuntimeException.class);
        assertThat(count("reconciliation.runs", "status", "FAILED")).isEqualTo(failed + 1);
        assertThat(count("reconciliation.runs", "status", "COMPLETED")).isEqualTo(completed);
        assertThat(count("reconciliation.runs", "status", "HALTED")).isEqualTo(halted);
        assertThat(meters.timer("reconciliation.duration").count()).isEqualTo(timed + 1);

        var finished = reconciliation.run(tenant, new ByteArrayInputStream(matchingCsv(tenant, "emp-recon").getBytes(StandardCharsets.UTF_8)));
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(count("reconciliation.runs", "status", "COMPLETED")).isEqualTo(completed + 1);
        assertThat(count("reconciliation.runs", "status", "FAILED")).isEqualTo(failed + 1);
        assertThat(count("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED")).isEqualTo(haltMeter);

        var stopped = reconciliation.run(tenant, new ByteArrayInputStream(
                """
                        tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift
                        %s,emp-other,1,Other,other@example.com,+15550000009,M1,%s,DAY
                        """.formatted(tenant, OFFICE).getBytes(StandardCharsets.UTF_8)
        ));
        assertThat(stopped.status()).isEqualTo("HALTED");
        assertThat(count("reconciliation.runs", "status", "HALTED")).isEqualTo(halted + 1);
        assertThat(count("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED")).isEqualTo(haltMeter + 1);
        assertThat(count("reconciliation.runs", "status", "APPROVED")).isEqualTo(approved);

        reconciliation.approve(stopped.id(), tenant);
        assertThat(count("reconciliation.runs", "status", "APPROVED")).isEqualTo(approved + 1);
        assertThat(count("reconciliation.runs", "status", "HALTED")).isEqualTo(halted + 1);

        String scrape = prometheus(operatorHeaders());
        assertThat(scrape).contains("reconciliation_duration_seconds_bucket");
        assertThat(scrape).doesNotContain("emp-recon", "ada@example.com", "+15550000001");
    }

    @Test
    void outboxAndDeactivationCountersMoveOnThoseEvents() {
        String tenant = "metrics-cab";
        saveOffice(tenant);
        bookings.seedTrip(tenant, "trip-metrics", 2);
        syncEvents.ingest(List.of(new SyncEvent(
                tenant, "emp-cab", 1L, EventType.CREATE,
                new EmployeeFields("Ada", "cab@example.com", "+15550000002", "M1", OFFICE, null, null, "DAY")
        )));
        bookings.seedBooking("later-metrics", tenant, "emp-cab", "trip-metrics", clock.instant().plus(Duration.ofDays(1)));
        double cancelled = count("deactivation.bookings.cancelled");
        double seats = count("deactivation.seats.freed");
        double failures = count("deactivation.effect.failures");
        double published = count("outbox.published");
        double retries = count("outbox.retries");

        syncEvents.ingest(List.of(new SyncEvent(
                tenant, "emp-cab", 2L, EventType.DEACTIVATE, EmployeeFields.empty()
        )));
        assertThat(poller.poll()).isEqualTo(2);
        assertThat(count("deactivation.bookings.cancelled")).isEqualTo(cancelled + 1);
        assertThat(count("deactivation.seats.freed")).isEqualTo(seats + 1);
        assertThat(count("deactivation.effect.failures")).isEqualTo(failures);
        assertThat(count("outbox.published")).isEqualTo(published + 2);
        assertThat(count("outbox.retries")).isEqualTo(retries);

        drain();
        published = count("outbox.published");
        retries = count("outbox.retries");
        seats = count("deactivation.seats.freed");
        syncEvents.ingest(List.of(new SyncEvent(
                tenant, "emp-cab-fail", 1L, EventType.CREATE,
                new EmployeeFields("Bea", "bea@example.com", "+15550000003", "M1", OFFICE, null, null, "DAY")
        )));
        syncEvents.ingest(List.of(new SyncEvent(
                tenant, "emp-cab-fail", 2L, EventType.DEACTIVATE, EmployeeFields.empty()
        )));
        deactivations.setAfterCancel(() -> {
            throw new IllegalStateException("effect failed");
        });
        assertThat(poller.poll()).isEqualTo(1);
        assertThat(count("deactivation.effect.failures")).isEqualTo(failures + 1);
        assertThat(count("deactivation.seats.freed")).isEqualTo(seats);
        assertThat(count("outbox.retries")).isEqualTo(retries + 1);
        deactivations.setAfterCancel(null);
    }

    @Test
    void gaugesSampleIndexedQueriesAndKeepDepth() {
        drain();
        jdbc.update("delete from outbox");
        outboxMetrics.sample();
        assertThat(meters.get("outbox.pending").gauge().value()).isZero();
        assertThat(meters.get("outbox.failed").gauge().value()).isZero();
        assertThat(meters.get("outbox.oldest.pending.seconds").gauge().value()).isZero();

        jdbc.update(
                """
                        insert into outbox (tenant_id, aggregate_id, event_type, payload, created_at, status, next_attempt_at)
                        values ('gauge', 'emp-gauge', 'EMPLOYEE_DEACTIVATED', '{}'::jsonb, clock_timestamp() - interval '40 seconds', 'PENDING', clock_timestamp() + interval '1 day')
                        """
        );
        jdbc.update(
                """
                        insert into outbox (tenant_id, aggregate_id, event_type, payload, created_at, status, attempts)
                        values ('gauge', 'emp-gauge-failed', 'EMPLOYEE_DEACTIVATED', '{}'::jsonb, clock_timestamp(), 'FAILED', 3)
                        """
        );
        outboxMetrics.sample();
        assertThat(meters.get("outbox.pending").gauge().value()).isEqualTo(1);
        assertThat(meters.get("outbox.oldest.pending.seconds").gauge().value()).isGreaterThanOrEqualTo(30);
        assertThat(meters.get("outbox.failed").gauge().value()).isEqualTo(1);

        explain("pending-depth", OutboxMetrics.PENDING_DEPTH);
        explain("oldest-pending", OutboxMetrics.OLDEST_PENDING_SECONDS);
        jdbc.update("delete from outbox where tenant_id = 'gauge'");
    }

    @Test
    void authCountersMoveWithoutNamingTheUser() {
        int previous = authSettings.getMaxLoginFailures();
        authSettings.setMaxLoginFailures(2);
        try {
            double failures = count("auth.login.failures");
            double lockouts = count("auth.lockouts");
            double rejected = count("auth.rejected.tokens");
            restTemplate.postForEntity("/auth/token", Map.of("username", "nobody-metrics", "password", PASSWORD), String.class);
            assertThat(count("auth.login.failures")).isEqualTo(failures + 1);
            assertThat(count("auth.lockouts")).isEqualTo(lockouts);
            restTemplate.postForEntity("/auth/token", Map.of("username", "nobody-metrics", "password", PASSWORD), String.class);
            assertThat(count("auth.login.failures")).isEqualTo(failures + 2);
            assertThat(count("auth.lockouts")).isEqualTo(lockouts + 1);

            HttpHeaders bad = new HttpHeaders();
            bad.setBearerAuth(PHONE + ".token");
            ResponseEntity<String> denied = restTemplate.exchange("/health", HttpMethod.GET, new HttpEntity<>(bad), String.class);
            assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(count("auth.rejected.tokens")).isEqualTo(rejected + 1);

            String scrape = prometheus(operatorHeaders());
            assertThat(scrape).doesNotContain(PASSWORD, "nobody-metrics", PHONE);
        } finally {
            authSettings.setMaxLoginFailures(previous);
        }
    }

    @Test
    void prometheusRefusesAnyoneWhoIsNotAnOperator() {
        HttpHeaders anonymousHeaders = new HttpHeaders();
        anonymousHeaders.set(HttpHeaders.ACCEPT, "text/plain");
        ResponseEntity<String> anonymous = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(anonymousHeaders), String.class
        );
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        HttpHeaders admin = adminHeaders();
        admin.set(HttpHeaders.ACCEPT, "text/plain");
        ResponseEntity<String> adminResponse = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(admin), String.class
        );
        assertThat(adminResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders employee = employeeHeaders();
        employee.set(HttpHeaders.ACCEPT, "text/plain");
        ResponseEntity<String> employeeResponse = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(employee), String.class
        );
        assertThat(employeeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        HttpHeaders operator = operatorHeaders();
        operator.set(HttpHeaders.ACCEPT, "text/plain");
        ResponseEntity<String> operatorResponse = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(operator), String.class
        );
        assertThat(operatorResponse.getStatusCode())
                .withFailMessage(() -> "status=" + operatorResponse.getStatusCode() + " body=" + operatorResponse.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(operatorResponse.getBody()).contains("auth_login_failures");
    }

    private void explain(String label, String sql) {
        List<String> natural = plan(sql, false);
        List<String> indexed = plan(sql, true);
        System.out.println("outbox gauge explain " + label + " natural:");
        natural.forEach(System.out::println);
        System.out.println("outbox gauge explain " + label + " index:");
        indexed.forEach(System.out::println);
        String indexedPlan = String.join("\n", indexed).toLowerCase();
        assertThat(indexedPlan).contains("index");
        assertThat(indexedPlan).contains("idx_outbox_pending");
        assertThat(indexedPlan).doesNotContain("seq scan");
    }

    private List<String> plan(String sql, boolean forceIndex) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        return tx.execute(status -> {
            if (forceIndex) {
                jdbc.execute("set local enable_seqscan = off");
            }
            return jdbc.query("explain " + sql, (rs, row) -> rs.getString(1));
        });
    }

    private void drain() {
        int spins = 0;
        while (poller.poll() > 0) {
            if (++spins > 50) {
                throw new IllegalStateException("outbox did not drain");
            }
        }
    }

    private String matchingCsv(String tenant, String employeeId) {
        Map<String, Object> row = jdbc.queryForMap(
                """
                        select hr_version, name, email, phone, manager, office_code, shift
                        from employee where tenant_id = ? and employee_id = ?
                        """,
                tenant,
                employeeId
        );
        return "tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift\n"
                + tenant + "," + employeeId + "," + row.get("hr_version") + ","
                + row.get("name") + "," + row.get("email") + "," + row.get("phone") + ","
                + row.get("manager") + "," + row.get("office_code") + "," + row.get("shift") + "\n";
    }

    private SyncEvent create(long version) {
        return new SyncEvent(
                TENANT,
                EMPLOYEE,
                version,
                EventType.CREATE,
                new EmployeeFields(NAME, EMAIL, PHONE, "M1", OFFICE, null, null, "DAY")
        );
    }

    private double count(String name, String... tags) {
        return meters.counter(name, tags).count();
    }

    private String prometheus(HttpHeaders headers) {
        headers.set(HttpHeaders.ACCEPT, "text/plain");
        ResponseEntity<String> response = restTemplate.exchange(
                "/actuator/prometheus", HttpMethod.GET, new HttpEntity<>(headers), String.class
        );
        assertThat(response.getStatusCode())
                .withFailMessage(() -> "status=" + response.getStatusCode() + " body=" + response.getBody())
                .isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders operatorHeaders() {
        return TestAccounts.operator(jdbc, passwordEncoder, restTemplate, mapper);
    }

    private HttpHeaders adminHeaders() {
        return TestAccounts.admin(jdbc, passwordEncoder, restTemplate, mapper, "metrics-admin");
    }

    private HttpHeaders employeeHeaders() {
        return TestAccounts.employee(jdbc, passwordEncoder, restTemplate, mapper, "metrics-admin", "emp-self");
    }

    private void saveOffice(String tenant) {
        if (!offices.existsByTenantIdAndCode(tenant, OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), tenant, OFFICE));
        }
    }
}
