package com.moveinsync.hrsync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.api.SyncBatchRequest;
import com.moveinsync.hrsync.api.SyncEventRequest;
import com.moveinsync.hrsync.api.SyncFieldsRequest;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.repo.EmployeeEntity;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
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

    private static HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
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
