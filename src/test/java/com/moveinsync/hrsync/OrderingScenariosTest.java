package com.moveinsync.hrsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.api.SyncBatchRequest;
import com.moveinsync.hrsync.api.SyncEventRequest;
import com.moveinsync.hrsync.api.SyncFieldsRequest;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class OrderingScenariosTest {

    private static final String HYD = "HYD";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private EmployeeRepository employees;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seedOffices() {
        saveOffice(SampleHrBatch.OFFICE);
        saveOffice(HYD);
    }

    @Test
    void replayingAFeedLeavesTheDatabaseUnchanged() throws Exception {
        SyncEventRequest[] feed = {
                hr("emp-replay-1", 1, "CREATE", "First", SampleHrBatch.OFFICE),
                hr("emp-replay-1", 2, "UPDATE", "Second", SampleHrBatch.OFFICE),
                hr("emp-replay-2", 1, "CREATE", "Other", SampleHrBatch.OFFICE)
        };

        JsonNode first = post("/sync/events", feed);
        assertThat(first).allSatisfy(result -> assertThat(result.get("outcome").asText()).isEqualTo("APPLIED"));
        List<StoredEmployee> before = List.of(stored("emp-replay-1"), stored("emp-replay-2"));

        JsonNode second = post("/sync/events", feed);

        assertThat(second).hasSize(3);
        second.forEach(OrderingScenariosTest::assertNoOp);
        assertThat(List.of(stored("emp-replay-1"), stored("emp-replay-2"))).isEqualTo(before);
    }

    @Test
    void laterOfficeTransferWinsWhenAnEarlierChangeArrivesAfterIt() throws Exception {
        JsonNode transfer = post("/sync/events", hr("emp-xfer", 7, "UPDATE", "Evening", HYD));
        assertThat(transfer.get(0).get("outcome").asText()).isEqualTo("APPLIED");

        JsonNode earlier = post("/sync/events", hr("emp-xfer", 6, "UPDATE", "Morning", SampleHrBatch.OFFICE));

        assertThat(earlier.get(0).get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(earlier.get(0).get("reasonCode").asText()).isEqualTo("STALE_IGNORED");
        StoredEmployee employee = stored("emp-xfer");
        assertThat(employee.officeCode()).isEqualTo(HYD);
        assertThat(employee.name()).isEqualTo("Evening");
        assertThat(employee.hrVersion()).isEqualTo(7);
    }

    @Test
    void officeTransferBeforeAnEarlierShiftChangeKeepsTheVersion7Shift() throws Exception {
        post("/sync/events", hr("emp-shift", 1, "CREATE", "Ada", SampleHrBatch.OFFICE, "MORNING"));
        JsonNode transfer = post("/sync/events", hr("emp-shift", 7, "UPDATE", "Ada", HYD, "NIGHT"));
        assertThat(transfer.get(0).get("outcome").asText()).isEqualTo("APPLIED");

        JsonNode earlier = post("/sync/events", hr("emp-shift", 6, "UPDATE", null, null, "DAY"));

        assertThat(earlier.get(0).get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(earlier.get(0).get("reasonCode").asText()).isEqualTo("STALE_IGNORED");
        StoredEmployee employee = stored("emp-shift");
        assertThat(employee.officeCode()).isEqualTo(HYD);
        assertThat(employee.shift()).isEqualTo("NIGHT");
        assertThat(employee.hrVersion()).isEqualTo(7);
    }

    @Test
    void malformedShiftIsRejected() throws Exception {
        JsonNode rejected = post("/sync/events", hr("emp-bad-shift", 1, "CREATE", "Ada", SampleHrBatch.OFFICE, "bad!"));

        assertThat(rejected.get(0).get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get(0).get("reasonCode").asText()).isEqualTo("INVALID_SHIFT");
        assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-bad-shift")).isEmpty();
    }

    @Test
    void concurrentVersionsKeepTheHighestAndDoNotEscape() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<IngestResult>>> tasks = new ArrayList<>();
        try {
            for (int version = 1; version <= 50; version++) {
                long sourceVersion = version;
                tasks.add(pool.submit(() -> {
                    start.await();
                    return syncEvents.ingest(List.of(new SyncEvent(
                            SampleHrBatch.TENANT,
                            "emp-race",
                            sourceVersion,
                            EventType.UPDATE,
                            new EmployeeFields("v" + sourceVersion, null, null, null, null, null, null, null)
                    )));
                }));
            }
            start.countDown();
            List<IngestResult> results = new ArrayList<>();
            for (Future<List<IngestResult>> task : tasks) {
                results.addAll(task.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(50);
            assertThat(results).allSatisfy(result -> assertThat(result.outcome()).isNotEqualTo(Outcome.REJECTED));
        } finally {
            pool.shutdownNow();
        }

        StoredEmployee employee = stored("emp-race");
        assertThat(employee.hrVersion()).isEqualTo(50);
        assertThat(employee.name()).isEqualTo("v50");
    }

    @Test
    void twentyThreadsCreatingTheSameNewEmployeeDoNotFailOnTheUniqueKey() throws Exception {
        assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-twin")).isEmpty();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<IngestResult>>> tasks = new ArrayList<>();
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                tasks.add(pool.submit(() -> {
                    start.await();
                    return syncEvents.ingest(List.of(new SyncEvent(
                            SampleHrBatch.TENANT,
                            "emp-twin",
                            1L,
                            EventType.CREATE,
                            new EmployeeFields("Twin", "emp-twin@example.com", "+15550000001", "M1", SampleHrBatch.OFFICE, null, null, null)
                    )));
                }));
            }
            start.countDown();
            List<IngestResult> results = new ArrayList<>();
            for (Future<List<IngestResult>> task : tasks) {
                results.addAll(task.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).hasSize(20);
            assertThat(results).noneMatch(result ->
                    result.outcome() == Outcome.REJECTED || result.reasonCode() == ReasonCode.APPLY_FAILED);
            assertThat(results).filteredOn(result -> result.outcome() == Outcome.APPLIED).hasSize(1);
            assertThat(results).filteredOn(result -> result.outcome() == Outcome.IGNORED)
                    .hasSize(19)
                    .allSatisfy(result -> assertThat(result.reasonCode()).isIn(ReasonCode.DUPLICATE, ReasonCode.STALE_IGNORED));
        } finally {
            pool.shutdownNow();
        }

        StoredEmployee employee = stored("emp-twin");
        assertThat(employee.hrVersion()).isEqualTo(1);
        assertThat(employee.status()).isEqualTo("ACTIVE");
        assertThat(employee.name()).isEqualTo("Twin");
    }

    @Test
    void hrUpdateDoesNotChangeTransportFields() throws Exception {
        post("/sync/events", create("emp-own", 1, "Ada"));
        JsonNode transport = post("/sync/transport-events", new SyncEventRequest(
                SampleHrBatch.TENANT,
                "emp-own",
                1L,
                "UPDATE",
                new SyncFieldsRequest("Stolen", "stolen@example.com", "not-a-phone", "X", "ZZZ", "Gate 4", "wheelchair", "STOLEN")
        ));
        assertThat(transport.get(0).get("outcome").asText()).isEqualTo("APPLIED");

        JsonNode hr = post("/sync/events", new SyncEventRequest(
                SampleHrBatch.TENANT,
                "emp-own",
                2L,
                "UPDATE",
                new SyncFieldsRequest("Ada Lovelace", null, null, null, null, "Home Lane", "none", null)
        ));

        assertThat(hr.get(0).get("outcome").asText()).isEqualTo("APPLIED");
        StoredEmployee employee = stored("emp-own");
        assertThat(employee.name()).isEqualTo("Ada Lovelace");
        assertThat(employee.pickupPoint()).isEqualTo("Gate 4");
        assertThat(employee.specialAssistance()).isEqualTo("wheelchair");
        assertThat(employee.hrVersion()).isEqualTo(2);
        assertThat(employee.transportVersion()).isEqualTo(1);
        assertThat(employee.email()).isEqualTo("emp-own@example.com");
        assertThat(employee.shift()).isNull();
    }

    @Test
    void updateOrDeactivateBeforeCreateRecordsAVersionTombstone() throws Exception {
        long activeBefore = employees.countByTenantIdAndStatus(SampleHrBatch.TENANT, EmployeeStatus.ACTIVE);
        post("/sync/events", hr("emp-tomb-u", 5, "UPDATE", "Later", SampleHrBatch.OFFICE));
        JsonNode earlyCreate = post("/sync/events", hr("emp-tomb-u", 3, "CREATE", "Early", SampleHrBatch.OFFICE));

        assertThat(earlyCreate.get(0).get("reasonCode").asText()).isEqualTo("STALE_IGNORED");
        StoredEmployee updated = stored("emp-tomb-u");
        assertThat(updated.hrVersion()).isEqualTo(5);
        assertThat(updated.name()).isEqualTo("Later");
        assertThat(updated.status()).isEqualTo("PLACEHOLDER");
        assertThat(employees.findVisibleByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-tomb-u")).isEmpty();
        assertThat(employees.countByTenantIdAndStatus(SampleHrBatch.TENANT, EmployeeStatus.ACTIVE)).isEqualTo(activeBefore);

        JsonNode realCreate = post("/sync/events", hr("emp-tomb-u", 8, "CREATE", "Real", SampleHrBatch.OFFICE));
        assertThat(realCreate.get(0).get("outcome").asText()).isEqualTo("APPLIED");
        StoredEmployee created = stored("emp-tomb-u");
        assertThat(created.status()).isEqualTo("ACTIVE");
        assertThat(created.name()).isEqualTo("Real");
        assertThat(created.hrVersion()).isEqualTo(8);
        assertThat(employees.findVisibleByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-tomb-u")).isPresent();
        assertThat(employees.countByTenantIdAndStatus(SampleHrBatch.TENANT, EmployeeStatus.ACTIVE)).isEqualTo(activeBefore + 1);

        post("/sync/events", new SyncEventRequest(SampleHrBatch.TENANT, "emp-tomb-d", 4L, "DEACTIVATE", null));
        JsonNode earlyAfterDeactivate = post("/sync/events", hr("emp-tomb-d", 2, "CREATE", "Early", SampleHrBatch.OFFICE));

        assertThat(earlyAfterDeactivate.get(0).get("reasonCode").asText()).isEqualTo("STALE_IGNORED");
        StoredEmployee deactivated = stored("emp-tomb-d");
        assertThat(deactivated.status()).isEqualTo("DEACTIVATED");
        assertThat(deactivated.hrVersion()).isEqualTo(4);
        assertThat(deactivated.name()).isNull();

        JsonNode newerCreate = post("/sync/events", hr("emp-tomb-d", 6, "CREATE", "Joined", SampleHrBatch.OFFICE));
        assertThat(newerCreate.get(0).get("outcome").asText()).isEqualTo("APPLIED");
        StoredEmployee joined = stored("emp-tomb-d");
        assertThat(joined.status()).isEqualTo("ACTIVE");
        assertThat(joined.name()).isEqualTo("Joined");
        assertThat(joined.hrVersion()).isEqualTo(6);
    }

    @Test
    void oneBatchOfOutOfOrderEventsEndsAtTheHighestVersion() throws Exception {
        JsonNode results = post(
                "/sync/events",
                hr("emp-batch", 1, "UPDATE", "v1", SampleHrBatch.OFFICE),
                hr("emp-batch", 5, "UPDATE", "v5", HYD),
                hr("emp-batch", 3, "UPDATE", "v3", SampleHrBatch.OFFICE),
                hr("emp-batch", 4, "UPDATE", "v4", SampleHrBatch.OFFICE),
                hr("emp-batch", 2, "UPDATE", "v2", SampleHrBatch.OFFICE)
        );

        assertThat(results).extracting(node -> node.get("sourceVersion").asLong()).containsExactly(1L, 5L, 3L, 4L, 2L);
        assertThat(results).allSatisfy(result -> assertThat(result.get("outcome").asText()).isEqualTo("APPLIED"));
        StoredEmployee employee = stored("emp-batch");
        assertThat(employee.hrVersion()).isEqualTo(5);
        assertThat(employee.name()).isEqualTo("v5");
        assertThat(employee.officeCode()).isEqualTo(HYD);
    }

    private JsonNode post(String path, SyncEventRequest... events) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = restTemplate.postForEntity(
                path,
                new HttpEntity<>(new SyncBatchRequest(List.of(events)), headers),
                String.class
        );
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("@example.com", "+1555", "Gate 4", "wheelchair", "Home Lane");
        return objectMapper.readTree(response.getBody()).get("results");
    }

    private StoredEmployee stored(String employeeId) {
        return jdbc.queryForObject(
                """
                        select name, email, phone, office_code, shift, pickup_point, special_assistance,
                               hr_version, transport_version, status, updated_at
                        from employee
                        where tenant_id = ? and employee_id = ?
                        """,
                (rs, row) -> new StoredEmployee(
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("office_code"),
                        rs.getString("shift"),
                        rs.getString("pickup_point"),
                        rs.getString("special_assistance"),
                        rs.getLong("hr_version"),
                        rs.getLong("transport_version"),
                        rs.getString("status"),
                        rs.getTimestamp("updated_at")
                ),
                SampleHrBatch.TENANT,
                employeeId
        );
    }

    private void saveOffice(String code) {
        if (!offices.existsByTenantIdAndCode(SampleHrBatch.TENANT, code)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), SampleHrBatch.TENANT, code));
        }
    }

    private static SyncEventRequest create(String employeeId, long version, String name) {
        return hr(employeeId, version, "CREATE", name, SampleHrBatch.OFFICE);
    }

    private static SyncEventRequest hr(String employeeId, long version, String type, String name, String office) {
        return hr(employeeId, version, type, name, office, null);
    }

    private static SyncEventRequest hr(
            String employeeId,
            long version,
            String type,
            String name,
            String office,
            String shift
    ) {
        return new SyncEventRequest(
                SampleHrBatch.TENANT,
                employeeId,
                version,
                type,
                new SyncFieldsRequest(name, employeeId + "@example.com", "+15550000001", "M1", office, null, null, shift)
        );
    }

    private static void assertNoOp(JsonNode result) {
        // A replay of an applied version is DUPLICATE because processed_event already holds
        // that tenant, employee, channel, and source version. STALE_IGNORED is a version
        // that was never applied and is not newer than the stored channel version.
        assertThat(result.get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(result.get("reasonCode").asText()).isEqualTo("DUPLICATE");
    }

    private record StoredEmployee(
            String name,
            String email,
            String phone,
            String officeCode,
            String shift,
            String pickupPoint,
            String specialAssistance,
            long hrVersion,
            long transportVersion,
            String status,
            Timestamp updatedAt
    ) {
    }
}
