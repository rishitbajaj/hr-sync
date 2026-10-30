package com.moveinsync.hrsync.cache;

import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.JpaOfficeDirectory;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.EmployeeChangeService;
import com.moveinsync.hrsync.service.SyncEventService;
import io.micrometer.core.instrument.MeterRegistry;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.jpa.properties.hibernate.generate_statistics=true"
)
@Testcontainers
class ReadCacheTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private EmployeeReads employeeReads;

    @Autowired
    private JpaOfficeDirectory offices;

    @Autowired
    private OfficeRepository officeRepository;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private EmployeeChangeService changes;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionFactory sessionFactory;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aCachedEmployeeReadDoesNotQueryAgain() {
        office("cache-hit", "BLR");
        create("cache-hit", "emp-hit", "BLR", "DAY", 1);
        assertThat(employeeReads.get("cache-hit", "emp-hit")).isPresent();
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();
        assertThat(employeeReads.get("cache-hit", "emp-hit")).get().extracting(CachedEmployee::shift).isEqualTo("DAY");
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    void aWriteEvictsAndTheNextReadSeesTheNewShift() {
        office("cache-write", "BLR");
        create("cache-write", "emp-write", "BLR", "DAY", 1);
        assertThat(read("cache-write", "emp-write").shift()).isEqualTo("DAY");
        long lookups = employeeReads.lookups();
        create("cache-write", "emp-write", "BLR", "NIGHT", 2);
        assertThat(employeeReads.lookups()).isEqualTo(lookups);
        assertThat(read("cache-write", "emp-write").shift()).isEqualTo("NIGHT");
    }

    @Test
    void tenantsDoNotShareACachedEmployee() {
        office("cache-a", "BLR");
        office("cache-b", "NYC");
        create("cache-a", "emp-same", "BLR", "DAY", 1);
        create("cache-b", "emp-same", "NYC", "NIGHT", 1);
        assertThat(read("cache-a", "emp-same").officeCode()).isEqualTo("BLR");
        assertThat(read("cache-b", "emp-same").officeCode()).isEqualTo("NYC");
    }

    @Test
    void aPlaceholderIsNeverServedOrRemembered() {
        office("cache-ph", "BLR");
        jdbc.update(
                """
                        insert into employee (
                            id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                            pickup_point, special_assistance, hr_version, transport_version, status, updated_at
                        ) values (?, 'cache-ph', 'emp-ph', 'Hidden', 'hidden@example.com', '+15550000001', 'M1', 'BLR', 'DAY',
                                  null, null, 1, 0, 'PLACEHOLDER', ?)
                        """,
                UUID.randomUUID(),
                Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"))
        );
        assertThat(get("cache-ph", "emp-ph").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        jdbc.update("update employee set status = 'ACTIVE' where tenant_id = 'cache-ph' and employee_id = 'emp-ph'");
        ResponseEntity<String> visible = get("cache-ph", "emp-ph");
        assertThat(visible.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(visible.getBody()).contains("ACTIVE").doesNotContain("PLACEHOLDER").doesNotContain("hidden@example.com");
    }

    @Test
    void aDeactivationIsVisibleOnTheNextRead() {
        office("cache-off", "BLR");
        create("cache-off", "emp-off", "BLR", "DAY", 1);
        assertThat(read("cache-off", "emp-off").status()).isEqualTo("ACTIVE");
        assertThat(changes.deactivateIfUnchanged("cache-off", "emp-off", 1)).isTrue();
        assertThat(read("cache-off", "emp-off").status()).isEqualTo("DEACTIVATED");
    }

    @Test
    void anUnknownOfficeIsNotRememberedAndAKnownOneIs() {
        office("cache-office", "BLR");
        Statistics statistics = sessionFactory.getStatistics();
        statistics.clear();
        assertThat(offices.exists("cache-office", "NOPE")).isFalse();
        assertThat(offices.exists("cache-office", "NOPE")).isFalse();
        assertThat(statistics.getQueryExecutionCount()).isEqualTo(2);

        assertThat(offices.exists("cache-office", "BLR")).isTrue();
        statistics.clear();
        assertThat(offices.exists("cache-office", "BLR")).isTrue();
        assertThat(statistics.getQueryExecutionCount()).isZero();
    }

    @Test
    void cacheMetersDoNotCarryPersonalValues() {
        office("cache-meter", "BLR");
        create("cache-meter", "emp-meter", "BLR", "DAY", 1);
        employeeReads.get("cache-meter", "emp-meter");
        employeeReads.get("cache-meter", "emp-meter");
        assertThat(meters.counter("cache.gets", "cache", "employees", "result", "hit").count()).isPositive();
        assertThat(meters.getMeters())
                .extracting(meter -> meter.getId().toString())
                .noneMatch(id -> id.contains("emp-meter") || id.contains("cache-meter") || id.contains("@"));
    }

    private void office(String tenant, String code) {
        if (!officeRepository.existsByTenantIdAndCode(tenant, code)) {
            officeRepository.save(new OfficeEntity(UUID.randomUUID(), tenant, code));
        }
    }

    private void create(String tenant, String employeeId, String office, String shift, long version) {
        long before = employeeReads.lookups();
        syncEvents.ingest(List.of(new SyncEvent(
                tenant,
                employeeId,
                version,
                version == 1 ? EventType.CREATE : EventType.UPDATE,
                new EmployeeFields("Cache Person", employeeId + "@example.com", "+15550000001", "M1", office, null, null, shift)
        )));
        assertThat(employeeReads.lookups()).isEqualTo(before);
    }

    private CachedEmployee read(String tenant, String employeeId) {
        ResponseEntity<String> response = get(tenant, employeeId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            var body = objectMapper.readTree(response.getBody());
            return new CachedEmployee(
                    body.get("tenantId").asText(),
                    body.get("employeeId").asText(),
                    body.get("status").asText(),
                    body.get("officeCode").asText(),
                    body.get("shift").asText()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseEntity<String> get(String tenant, String employeeId) {
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, tenant);
        return restTemplate.exchange(
                "/employees/" + employeeId + "?tenantId=" + tenant,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
    }
}
