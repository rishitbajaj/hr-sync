package com.moveinsync.hrsync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.api.SyncBatchRequest;
import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.api.SyncEventRequest;
import com.moveinsync.hrsync.api.SyncFieldsRequest;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.EmployeeEntity;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.observability.CorrelationIds;
import com.moveinsync.hrsync.service.PersistGate;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SyncEventsHttpTest {

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
    private ObjectMapper objectMapper;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PersistGate persistGate;

    @BeforeEach
    void seedOffice() {
        if (!offices.existsByTenantIdAndCode(SampleHrBatch.TENANT, SampleHrBatch.OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), SampleHrBatch.TENANT, SampleHrBatch.OFFICE));
        }
    }

    @Test
    void batchOfTenAcceptsSevenAndRejectsThreeWithoutPersonalData() throws Exception {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            HttpHeaders headers = jsonHeaders();
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/sync/events",
                    new HttpEntity<>(SampleHrBatch.request(), headers),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            String body = response.getBody();
            SampleHrBatch.assertNoPersonalData(body);
            assertThat(body).doesNotContain("\"phone\"", "\"email\"", "\"pickupPoint\"", "\"name\"");

            JsonNode results = objectMapper.readTree(body).get("results");
            assertThat(results).hasSize(10);
            assertThat(count(results, "APPLIED")).isEqualTo(7);
            assertThat(count(results, "REJECTED")).isEqualTo(3);
            assertThat(results.get(1).get("employeeId").asText()).isEqualTo("emp-02");
            assertThat(results.get(1).get("reasonCode").asText()).isEqualTo("INVALID_PHONE");
            assertThat(results.get(4).get("employeeId").asText()).isEqualTo("emp-05");
            assertThat(results.get(4).get("reasonCode").asText()).isEqualTo("UNKNOWN_OFFICE");
            assertThat(results.get(8).get("employeeId").asText()).isEqualTo("emp-09");
            assertThat(results.get(8).get("reasonCode").asText()).isEqualTo("MISSING_REQUIRED_FIELD");
            assertThat(results.get(9).get("outcome").asText()).isEqualTo("APPLIED");

            EmployeeEntity saved = employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-01").orElseThrow();
            assertThat(saved.getPhone()).isEqualTo("+15550000001");
            assertThat(saved.getEmail()).isEqualTo("emp-01@leak.example");
            assertThat(saved.getPickupPoint()).isNull();
            assertThat(saved.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(saved.getHrVersion()).isEqualTo(1);
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-02")).isEmpty();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-05")).isEmpty();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-09")).isEmpty();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "emp-10")).isPresent();

            assertThat(logs.list).isNotEmpty();
            logs.list.forEach(event -> {
                SampleHrBatch.assertNoPersonalData(event.getFormattedMessage());
                if (event.getThrowableProxy() != null) {
                    SampleHrBatch.assertNoPersonalData(event.getThrowableProxy().getMessage());
                }
            });
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void databaseFailureOnRecordFourDoesNotAbortTheRestOfTheBatch() throws Exception {
        List<SyncEventRequest> events = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            String name = index == 4 ? "N".repeat(250) : "Person " + index;
            String phoneSuffix = index < 10 ? "0" + index : Integer.toString(index);
            events.add(new SyncEventRequest(
                    SampleHrBatch.TENANT,
                    "row-" + index,
                    (long) index,
                    "CREATE",
                    new SyncFieldsRequest(
                            name,
                            "row-" + index + "@leak.example",
                            "+155500000" + phoneSuffix,
                            "M1",
                            SampleHrBatch.OFFICE,
                            null,
                            null,
                            null
                    )
            ));
        }

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/sync/events",
                new HttpEntity<>(new SyncBatchRequest(events), jsonHeaders()),
                String.class
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode results = objectMapper.readTree(response.getBody()).get("results");
        assertThat(results).hasSize(10);
        assertThat(results.get(3).get("employeeId").asText()).isEqualTo("row-4");
        assertThat(results.get(3).get("outcome").asText()).isEqualTo("REJECTED");
        assertThat(results.get(3).get("reasonCode").asText()).isEqualTo("APPLY_FAILED");
        for (int index = 0; index < 10; index++) {
            if (index == 3) {
                continue;
            }
            assertThat(results.get(index).get("outcome").asText()).isEqualTo("APPLIED");
        }
        assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "row-4")).isEmpty();
        for (int index = 5; index <= 10; index++) {
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "row-" + index)).isPresent();
        }
        for (int index = 1; index <= 3; index++) {
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "row-" + index)).isPresent();
        }
    }

    @Test
    void aFailedRecordInsideAnOuterTransactionDoesNotRollBackItsNeighbors() {
        List<SyncEvent> events = new ArrayList<>();
        for (int index = 1; index <= 10; index++) {
            String name = index == 4 ? "N".repeat(250) : "Person " + index;
            String phoneSuffix = index < 10 ? "0" + index : Integer.toString(index);
            events.add(new SyncEvent(
                    SampleHrBatch.TENANT,
                    "outer-" + index,
                    (long) index,
                    EventType.CREATE,
                    new EmployeeFields(
                            name,
                            "outer-" + index + "@leak.example",
                            "+155500000" + phoneSuffix,
                            "M1",
                            SampleHrBatch.OFFICE,
                            null,
                            null,
                            null
                    )
            ));
        }

        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        List<IngestResult> results = outer.execute(status -> syncEvents.ingest(events));

        assertThat(results).hasSize(10);
        assertThat(results.get(3).employeeId()).isEqualTo("outer-4");
        assertThat(results.get(3).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(results.get(3).reasonCode()).isEqualTo(ReasonCode.APPLY_FAILED);
        for (int index = 0; index < 10; index++) {
            if (index == 3) {
                continue;
            }
            assertThat(results.get(index).outcome()).isEqualTo(Outcome.APPLIED);
        }
        assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "outer-4")).isEmpty();
        for (int index = 1; index <= 10; index++) {
            if (index == 4) {
                continue;
            }
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "outer-" + index)).isPresent();
        }
    }

    @Test
    void databaseUnavailableForTheWholeBatchReturns503AndAppliesNothing() throws Exception {
        persistGate.failNext(new QueryTimeoutException("canceling statement due to statement timeout"));
        try {
            HttpHeaders headers = jsonHeaders();
            headers.set(CorrelationIds.HEADER, "trace-transport-down");
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/sync/transport-events",
                    new HttpEntity<>(new SyncBatchRequest(List.of(
                            transport("tr-1"),
                            transport("tr-2")
                    )), headers),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3");
            assertThat(response.getHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo("trace-transport-down");
            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.get("correlationId").asText()).isEqualTo("trace-transport-down");
            JsonNode results = body.get("results");
            assertThat(results).hasSize(2);
            assertThat(results.get(0).get("outcome").asText()).isEqualTo("RETRYABLE_ERROR");
            assertThat(results.get(0).get("reasonCode").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(results.get(1).get("outcome").asText()).isEqualTo("RETRYABLE_ERROR");
            assertThat(results.get(1).get("reasonCode").asText()).isEqualTo("NOT_PROCESSED");
            assertThat(response.getBody()).doesNotContain("REJECTED");
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "tr-1")).isEmpty();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "tr-2")).isEmpty();
        } finally {
            persistGate.failNext(null);
        }
    }

    @Test
    void aConnectionFailureInTheMiddleKeepsEarlierAppliesAndAResendWritesEachOnce() throws Exception {
        persistGate.failAfter(1, new CannotGetJdbcConnectionException("connection refused"));
        HttpHeaders headers = jsonHeaders();
        headers.set(CorrelationIds.HEADER, "trace-hr-middle");
        SyncBatchRequest batch = new SyncBatchRequest(List.of(
                create("infra-1"),
                create("infra-2"),
                create("infra-3")
        ));
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    "/sync/events",
                    new HttpEntity<>(batch, headers),
                    String.class
            );

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3");
            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.get("correlationId").asText()).isEqualTo("trace-hr-middle");
            JsonNode results = body.get("results");
            assertThat(results.get(0).get("outcome").asText()).isEqualTo("APPLIED");
            assertThat(results.get(1).get("outcome").asText()).isEqualTo("RETRYABLE_ERROR");
            assertThat(results.get(1).get("reasonCode").asText()).isEqualTo("DATABASE_UNAVAILABLE");
            assertThat(results.get(2).get("outcome").asText()).isEqualTo("RETRYABLE_ERROR");
            assertThat(results.get(2).get("reasonCode").asText()).isEqualTo("NOT_PROCESSED");
            assertThat(response.getBody()).doesNotContain("REJECTED");
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "infra-1")).isPresent();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "infra-2")).isEmpty();
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, "infra-3")).isEmpty();
            assertThat(countRows("processed_event", "infra-1")).isEqualTo(1);
            assertThat(countRows("outbox", "infra-1")).isEqualTo(1);
            assertThat(countRows("processed_event", "infra-2")).isZero();
            assertThat(countRows("outbox", "infra-3")).isZero();
        } finally {
            persistGate.failNext(null);
        }

        ResponseEntity<String> replay = restTemplate.postForEntity(
                "/sync/events",
                new HttpEntity<>(batch, jsonHeaders()),
                String.class
        );
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode replayed = objectMapper.readTree(replay.getBody()).get("results");
        assertThat(replayed.get(0).get("outcome").asText()).isEqualTo("IGNORED");
        assertThat(replayed.get(0).get("reasonCode").asText()).isEqualTo("DUPLICATE");
        assertThat(replayed.get(1).get("outcome").asText()).isEqualTo("APPLIED");
        assertThat(replayed.get(2).get("outcome").asText()).isEqualTo("APPLIED");
        for (String employeeId : List.of("infra-1", "infra-2", "infra-3")) {
            assertThat(employees.findByTenantIdAndEmployeeId(SampleHrBatch.TENANT, employeeId)).isPresent();
            assertThat(countRows("processed_event", employeeId)).isEqualTo(1);
            assertThat(countRows("outbox", employeeId)).isEqualTo(1);
        }
    }

    private SyncEventRequest create(String employeeId) {
        return new SyncEventRequest(
                SampleHrBatch.TENANT,
                employeeId,
                1L,
                "CREATE",
                new SyncFieldsRequest(
                        "Ada",
                        employeeId + "@leak.example",
                        "+15550000071",
                        "M1",
                        SampleHrBatch.OFFICE,
                        null,
                        null,
                        "DAY"
                )
        );
    }

    private static SyncEventRequest transport(String employeeId) {
        return new SyncEventRequest(
                SampleHrBatch.TENANT,
                employeeId,
                1L,
                "UPDATE",
                new SyncFieldsRequest(null, null, null, null, null, "Gate 4", null, null)
        );
    }

    private int countRows(String table, String employeeId) {
        String column = "outbox".equals(table) ? "aggregate_id" : "employee_id";
        Integer count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = ? and " + column + " = ?",
                Integer.class,
                SampleHrBatch.TENANT,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, SampleHrBatch.TENANT);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static long count(JsonNode results, String outcome) {
        long matches = 0;
        for (JsonNode result : results) {
            if (outcome.equals(result.get("outcome").asText())) {
                matches++;
            }
        }
        return matches;
    }
}
