package com.moveinsync.hrsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.ReconciliationSettings;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.ReconciliationHooks;
import com.moveinsync.hrsync.service.SyncEventService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconciliationRunTest {

    private static final String OFFICE = "BLR";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private ReconciliationHooks hooks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReconciliationSettings settings;

    @Autowired
    private MeterRegistry meters;

    @AfterEach
    void clearHook() {
        hooks.setBeforeApply(null);
        settings.setMaxMissingRatio(0.30);
    }

    @Test
    void identicalExportIsAnEmptyDiffAndChangesNothing() throws Exception {
        String tenant = "recon-same";
        saveOffice(tenant);
        create(tenant, "emp-quoted", 1, "Same, Person");
        create(tenant, "emp-plain", 1, "Plain Name");
        create(tenant, "emp-third", 1, "Third Name");
        pickup(tenant, "emp-quoted", "Gate 9");
        assertThat(syncEvents.ingest(List.of(new SyncEvent(
                tenant,
                "emp-ghost",
                4L,
                EventType.UPDATE,
                fields("emp-ghost", "Ghost Name")
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);

        List<Stored> before = stored(tenant);
        long outboxBefore = outboxCount(tenant);
        JsonNode run = postCsv(csv(tenant, before.stream().filter(row -> "ACTIVE".equals(row.status())).toList()));

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("unchanged").asInt()).isEqualTo(3);
        assertThat(run.get("toCreate").asInt()).isZero();
        assertThat(run.get("toUpdate").asInt()).isZero();
        assertThat(run.get("toDeactivate").asInt()).isZero();
        assertThat(run.get("missingCount").asInt()).isZero();
        assertThat(run.get("activeCount").asInt()).isEqualTo(3);
        assertThat(run.get("exportRowCount").asInt()).isEqualTo(3);
        assertThat(stored(tenant)).usingRecursiveComparison().isEqualTo(before);
        assertThat(outboxCount(tenant)).isEqualTo(outboxBefore);
        assertThat(status(tenant, "emp-ghost")).isEqualTo("PLACEHOLDER");

        JsonNode fetched = getRun(run.get("id").asText());
        assertThat(fetched.get("unchanged").asInt()).isEqualTo(3);
        assertThat(fetched.get("missingCount").asInt()).isZero();
    }

    @Test
    void exportCreatesUpdatesAndDeactivatesThroughTheApplier() throws Exception {
        String tenant = "recon-apply";
        saveOffice(tenant);
        create(tenant, "emp-same", 1, "Same Person");
        create(tenant, "emp-edit", 1, "Ada");
        pickup(tenant, "emp-edit", "Gate 9");
        create(tenant, "emp-old", 5, "Current");
        create(tenant, "emp-miss", 1, "Nola Shore");

        List<Stored> rows = new ArrayList<>();
        rows.add(find(tenant, "emp-same"));
        Stored edit = find(tenant, "emp-edit");
        rows.add(new Stored(
                edit.employeeId(),
                2,
                "Ada Lovelace",
                edit.email(),
                edit.phone(),
                edit.manager(),
                edit.officeCode(),
                edit.shift(),
                edit.pickupPoint(),
                edit.status(),
                edit.updatedAt()
        ));
        Stored stale = find(tenant, "emp-old");
        rows.add(new Stored(
                stale.employeeId(),
                2,
                "Old",
                stale.email(),
                stale.phone(),
                stale.manager(),
                stale.officeCode(),
                stale.shift(),
                stale.pickupPoint(),
                stale.status(),
                stale.updatedAt()
        ));
        rows.add(new Stored(
                "emp-new",
                1,
                "New Hire",
                "emp-new@example.com",
                "+15550000001",
                "M1",
                OFFICE,
                "DAY",
                null,
                "ACTIVE",
                null
        ));

        JsonNode run = postCsv(csv(tenant, rows));

        assertThat(run.get("unchanged").asInt()).isEqualTo(1);
        assertThat(run.get("toCreate").asInt()).isEqualTo(1);
        assertThat(run.get("toUpdate").asInt()).isEqualTo(2);
        assertThat(run.get("toDeactivate").asInt()).isEqualTo(1);
        assertThat(run.get("missingCount").asInt()).isEqualTo(1);
        assertThat(run.get("activeCount").asInt()).isEqualTo(4);

        Stored same = find(tenant, "emp-same");
        assertThat(same.hrVersion()).isEqualTo(1);
        assertThat(same.name()).isEqualTo("Same Person");
        Stored edited = find(tenant, "emp-edit");
        assertThat(edited.name()).isEqualTo("Ada Lovelace");
        assertThat(edited.hrVersion()).isEqualTo(2);
        assertThat(edited.pickupPoint()).isEqualTo("Gate 9");
        assertThat(edited.transportVersion()).isEqualTo(1);
        Stored old = find(tenant, "emp-old");
        assertThat(old.name()).isEqualTo("Current");
        assertThat(old.hrVersion()).isEqualTo(5);
        Stored missing = find(tenant, "emp-miss");
        assertThat(missing.status()).isEqualTo("DEACTIVATED");
        assertThat(missing.hrVersion()).isEqualTo(1);
        assertThat(missing.name()).isEqualTo("Nola Shore");
        Stored created = find(tenant, "emp-new");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.name()).isEqualTo("New Hire");
        assertThat(created.pickupPoint()).isNull();

        String payload = jdbc.queryForObject(
                """
                        select payload::text from outbox
                        where tenant_id = ? and aggregate_id = ? and event_type = 'DEACTIVATE'
                        """,
                String.class,
                tenant,
                "emp-miss"
        );
        assertThat(payload).contains("emp-miss").contains("status");
        assertThat(payload).doesNotContain("Nola").doesNotContain("Shore").doesNotContain("@example.com");
    }

    @Test
    void liveHrEventBetweenDiffAndApplyIsNotDeactivated() throws Exception {
        String tenant = "recon-live";
        saveOffice(tenant);
        create(tenant, "emp-live", 1, "Ada");
        pickup(tenant, "emp-live", "Gate 9");
        create(tenant, "emp-stay", 1, "Bea");
        create(tenant, "emp-stay-2", 1, "Bea");
        create(tenant, "emp-stay-3", 1, "Bea");
        create(tenant, "emp-stay-4", 1, "Bea");

        hooks.setBeforeApply(() -> assertThat(syncEvents.ingest(List.of(new SyncEvent(
                tenant,
                "emp-live",
                2L,
                EventType.UPDATE,
                new EmployeeFields("Live Winner", null, null, null, null, null, null, null)
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED));

        JsonNode run = postCsv(csv(tenant, List.of(
                find(tenant, "emp-stay"),
                find(tenant, "emp-stay-2"),
                find(tenant, "emp-stay-3"),
                find(tenant, "emp-stay-4")
        )));

        assertThat(run.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.get("unchanged").asInt()).isEqualTo(4);
        assertThat(run.get("toCreate").asInt()).isZero();
        assertThat(run.get("toUpdate").asInt()).isZero();
        assertThat(run.get("toDeactivate").asInt()).isEqualTo(1);
        assertThat(run.get("missingCount").asInt()).isEqualTo(1);
        assertThat(run.get("activeCount").asInt()).isEqualTo(5);

        Stored live = find(tenant, "emp-live");
        assertThat(live.status()).isEqualTo("ACTIVE");
        assertThat(live.name()).isEqualTo("Live Winner");
        assertThat(live.hrVersion()).isEqualTo(2);
        assertThat(live.pickupPoint()).isEqualTo("Gate 9");
        Stored stay = find(tenant, "emp-stay");
        assertThat(stay.name()).isEqualTo("Bea");
        assertThat(stay.hrVersion()).isEqualTo(1);
        assertThat(stay.status()).isEqualTo("ACTIVE");
        Long deactivations = jdbc.queryForObject(
                """
                        select count(*) from outbox
                        where tenant_id = ? and aggregate_id = ? and event_type = 'DEACTIVATE'
                        """,
                Long.class,
                tenant,
                "emp-live"
        );
        assertThat(deactivations).isZero();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void cutoffExportTripsTheSafetyBrake(CapturedOutput output) throws Exception {
        String tenant = "recon-brake";
        saveOffice(tenant);
        seedActive(tenant, 200_000);

        double before = meters.counter("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED").count();
        JsonNode run = postCsv(cutoffCsv(tenant, 120_000));

        assertThat(run.get("status").asText()).isEqualTo("HALTED");
        assertThat(run.get("missingCount").asInt()).isEqualTo(80_000);
        assertThat(run.get("activeCount").asInt()).isEqualTo(200_000);
        assertThat(run.get("exportRowCount").asInt()).isEqualTo(120_000);
        assertThat(run.get("unchanged").asInt()).isEqualTo(120_000);
        assertThat(run.get("toCreate").asInt()).isZero();
        assertThat(run.get("toUpdate").asInt()).isZero();
        assertThat(run.get("toDeactivate").asInt()).isEqualTo(80_000);
        assertThat(run.get("missingRatio").asDouble()).isCloseTo(0.40, within(0.000001));
        assertThat(run.get("threshold").asDouble()).isCloseTo(0.30, within(0.000001));
        assertThat(run.get("haltReason").asText()).isEqualTo("MISSING_RATIO_EXCEEDED");
        assertThat(deactivated(tenant)).isZero();
        assertThat(status(tenant, employeeId(150_000))).isEqualTo("ACTIVE");
        assertThat(meters.counter("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED").count())
                .isEqualTo(before + 1);
        assertThat(output.getOut()).contains("reason=MISSING_RATIO_EXCEEDED").contains("missingCount=80000");
        assertThat(output.getOut()).doesNotContain("Pat").doesNotContain("@example.com");

        JsonNode fetched = getRun(run.get("id").asText());
        assertThat(fetched.get("missingCount").asInt()).isEqualTo(80_000);
        assertThat(fetched.get("status").asText()).isEqualTo("HALTED");
    }

    @Test
    void missingRatioThresholdIsConfigurable() throws Exception {
        settings.setMaxMissingRatio(0.10);
        String strict = "recon-strict";
        seedRoster(strict, 10);
        assertThat(syncEvents.ingest(List.of(new SyncEvent(
                strict,
                "emp-ghost",
                4L,
                EventType.UPDATE,
                fields("emp-ghost", "Ghost Name")
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);
        JsonNode halted = postCsv(csv(strict, exportFirst(strict, 8)));
        assertThat(halted.get("status").asText()).isEqualTo("HALTED");
        assertThat(halted.get("missingCount").asInt()).isEqualTo(2);
        assertThat(halted.get("activeCount").asInt()).isEqualTo(10);
        assertThat(halted.get("threshold").asDouble()).isCloseTo(0.10, within(0.000001));
        assertThat(halted.get("haltReason").asText()).isEqualTo("MISSING_RATIO_EXCEEDED");
        assertThat(deactivated(strict)).isZero();
        assertThat(status(strict, "emp-ghost")).isEqualTo("PLACEHOLDER");
        assertThat(status(strict, "emp-9")).isEqualTo("ACTIVE");
        ResponseEntity<String> metric = restTemplate.getForEntity(
                "/actuator/metrics/reconciliation.halted",
                String.class
        );
        assertThat(metric.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metric.getBody()).contains("MISSING_RATIO_EXCEEDED");
        assertThat(metric.getBody()).doesNotContain("@example.com").doesNotContain("Ghost");

        settings.setMaxMissingRatio(0.50);
        String loose = "recon-loose";
        seedRoster(loose, 10);
        JsonNode applied = postCsv(csv(loose, exportFirst(loose, 8)));
        assertThat(applied.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(applied.get("missingCount").asInt()).isEqualTo(2);
        assertThat(applied.get("activeCount").asInt()).isEqualTo(10);
        assertThat(applied.get("threshold").asDouble()).isCloseTo(0.50, within(0.000001));
        assertThat(applied.get("haltReason").isNull()).isTrue();
        assertThat(deactivated(loose)).isEqualTo(2);
        assertThat(status(loose, "emp-8")).isEqualTo("DEACTIVATED");
        assertThat(status(loose, "emp-0")).isEqualTo("ACTIVE");
    }

    private JsonNode postCsv(byte[] csv) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/reconciliation/runs",
                new HttpEntity<>(csv, headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("@example.com", "Nola", "Gate 9", "Live Winner", "Same, Person");
        return objectMapper.readTree(response.getBody());
    }

    private JsonNode postCsv(String csv) throws Exception {
        return postCsv(csv.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode getRun(String id) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/reconciliation/runs/" + id, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("@example.com", "Nola", "Gate 9");
        return objectMapper.readTree(response.getBody());
    }

    private void create(String tenant, String employeeId, long version, String name) {
        assertThat(syncEvents.ingest(List.of(new SyncEvent(
                tenant,
                employeeId,
                version,
                EventType.CREATE,
                fields(employeeId, name)
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);
    }

    private void pickup(String tenant, String employeeId, String point) {
        assertThat(syncEvents.ingest(
                List.of(new SyncEvent(
                        tenant,
                        employeeId,
                        1L,
                        EventType.UPDATE,
                        new EmployeeFields(null, null, null, null, null, point, null, null)
                )),
                FieldChannel.TRANSPORT
        ).get(0).outcome()).isEqualTo(Outcome.APPLIED);
    }

    private static EmployeeFields fields(String employeeId, String name) {
        return new EmployeeFields(name, employeeId + "@example.com", "+15550000001", "M1", OFFICE, null, null, "DAY");
    }

    private void seedRoster(String tenant, int count) {
        saveOffice(tenant);
        for (int index = 0; index < count; index++) {
            create(tenant, "emp-" + index, 1, "Person " + index);
        }
    }

    private List<Stored> exportFirst(String tenant, int count) {
        return stored(tenant).stream()
                .filter(row -> row.employeeId().matches("emp-\\d+"))
                .filter(row -> Integer.parseInt(row.employeeId().substring("emp-".length())) < count)
                .toList();
    }

    private void seedActive(String tenant, int count) {
        Timestamp updatedAt = Timestamp.from(Instant.parse("2026-10-30T00:00:00Z"));
        String sql = """
                insert into employee (
                    id, tenant_id, employee_id, name, email, phone, manager, office_code, shift,
                    pickup_point, special_assistance, hr_version, transport_version, status, updated_at
                ) values (?, ?, ?, 'Pat', ?, '+15550000001', 'M1', 'BLR', 'DAY', null, null, 1, 0, 'ACTIVE', ?)
                """;
        int batch = 500;
        for (int start = 0; start < count; start += batch) {
            int size = Math.min(batch, count - start);
            int offset = start;
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    String employeeId = employeeId(offset + index);
                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, tenant);
                    statement.setString(3, employeeId);
                    statement.setString(4, employeeId + "@example.com");
                    statement.setTimestamp(5, updatedAt);
                }

                @Override
                public int getBatchSize() {
                    return size;
                }
            });
        }
    }

    private static byte[] cutoffCsv(String tenant, int rows) {
        ByteArrayOutputStream csv = new ByteArrayOutputStream(rows * 80);
        csv.writeBytes("tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift\n"
                .getBytes(StandardCharsets.UTF_8));
        for (int index = 0; index < rows; index++) {
            String employeeId = employeeId(index);
            String line = tenant + "," + employeeId + ",1,Pat," + employeeId
                    + "@example.com,+15550000001,M1,BLR,DAY\n";
            csv.writeBytes(line.getBytes(StandardCharsets.UTF_8));
        }
        return csv.toByteArray();
    }

    private static String employeeId(int index) {
        return "e%06d".formatted(index);
    }

    private long deactivated(String tenant) {
        Long count = jdbc.queryForObject(
                "select count(*) from employee where tenant_id = ? and status = 'DEACTIVATED'",
                Long.class,
                tenant
        );
        return count == null ? 0 : count;
    }

    private void saveOffice(String tenant) {
        if (!offices.existsByTenantIdAndCode(tenant, OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), tenant, OFFICE));
        }
    }

    private String csv(String tenant, List<Stored> rows) {
        StringBuilder csv = new StringBuilder(
                "tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift\n"
        );
        for (Stored row : rows) {
            csv.append(cell(tenant)).append(',')
                    .append(cell(row.employeeId())).append(',')
                    .append(row.hrVersion()).append(',')
                    .append(cell(row.name())).append(',')
                    .append(cell(row.email())).append(',')
                    .append(cell(row.phone())).append(',')
                    .append(cell(row.manager())).append(',')
                    .append(cell(row.officeCode())).append(',')
                    .append(cell(row.shift()))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String cell(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private Stored find(String tenant, String employeeId) {
        return stored(tenant).stream()
                .filter(row -> employeeId.equals(row.employeeId()))
                .findFirst()
                .orElseThrow();
    }

    private List<Stored> stored(String tenant) {
        return jdbc.query(
                """
                        select employee_id, hr_version, name, email, phone, manager, office_code, shift,
                               pickup_point, special_assistance, transport_version, status, updated_at
                        from employee
                        where tenant_id = ?
                        order by employee_id
                        """,
                (rs, row) -> new Stored(
                        rs.getString("employee_id"),
                        rs.getLong("hr_version"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("manager"),
                        rs.getString("office_code"),
                        rs.getString("shift"),
                        rs.getString("pickup_point"),
                        rs.getString("status"),
                        rs.getTimestamp("updated_at"),
                        rs.getLong("transport_version")
                ),
                tenant
        );
    }

    private String status(String tenant, String employeeId) {
        return jdbc.queryForObject(
                "select status from employee where tenant_id = ? and employee_id = ?",
                String.class,
                tenant,
                employeeId
        );
    }

    private long outboxCount(String tenant) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where tenant_id = ?",
                Long.class,
                tenant
        );
        return count == null ? 0 : count;
    }

    private record Stored(
            String employeeId,
            long hrVersion,
            String name,
            String email,
            String phone,
            String manager,
            String officeCode,
            String shift,
            String pickupPoint,
            String status,
            Timestamp updatedAt,
            long transportVersion
    ) {
        private Stored(
                String employeeId,
                long hrVersion,
                String name,
                String email,
                String phone,
                String manager,
                String officeCode,
                String shift,
                String pickupPoint,
                String status,
                Timestamp updatedAt
        ) {
            this(employeeId, hrVersion, name, email, phone, manager, officeCode, shift, pickupPoint, status, updatedAt, 0);
        }
    }
}
