package com.moveinsync.hrsync.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.auth.TestAccounts;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.OutboxPoller;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class CorrelationLoggingTest {

    private static final String TENANT = "log-co";
    private static final String EMPLOYEE = "emp-log";
    private static final String NAME = "Log Secret Name";
    private static final String EMAIL = "log-secret@leak.example";
    private static final String PHONE = "+15551238888";
    private static final String PICKUP = "Secret Log Gate";
    private static final String PASSWORD = "log-secret-password";
    private static final String TRACE = "trace-log-1";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void logsAreJsonAndTheCorrelationIdFollowsTheOutbox(CapturedOutput output) throws Exception {
        offices.save(new OfficeEntity(UUID.randomUUID(), TENANT, "BLR"));
        HttpHeaders headers = TestAccounts.admin(jdbc, passwordEncoder, restTemplate, objectMapper, TENANT);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(CorrelationIds.HEADER, TRACE);

        int outBefore = output.getOut().length();
        int errBefore = output.getErr().length();
        ResponseEntity<String> accepted = restTemplate.exchange(
                "/sync/events",
                HttpMethod.POST,
                new HttpEntity<>(
                        "{\"events\":[{\"tenantId\":\"" + TENANT + "\",\"employeeId\":\"" + EMPLOYEE
                                + "\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"" + NAME
                                + "\",\"email\":\"" + EMAIL + "\",\"phone\":\"" + PHONE
                                + "\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}",
                        headers
                ),
                String.class
        );
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo(TRACE);

        poller.poll();
        List<JsonNode> lines = jsonLines(output, outBefore, errBefore);
        assertThat(lines).isNotEmpty();
        lines.forEach(line -> {
            assertThat(line.hasNonNull("@timestamp")).isTrue();
            assertThat(line.hasNonNull("level")).isTrue();
            assertThat(line.hasNonNull("logger_name")).isTrue();
            assertThat(line.hasNonNull("message")).isTrue();
            assertThat(line.toString()).doesNotContain(NAME, EMAIL, PHONE, PICKUP, PASSWORD);
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(text(line, "correlationId")).as(line.toString()).isEqualTo(TRACE);
            assertThat(text(line, "tenantId")).isEqualTo(TENANT);
            assertThat(text(line, "employeeId")).isEqualTo(EMPLOYEE);
            assertThat(line.get("message").asText()).contains("applied");
        });
        assertThat(lines).anySatisfy(line -> {
            assertThat(text(line, "correlationId")).as(line.toString()).isEqualTo(TRACE);
            assertThat(text(line, "eventId")).isNotBlank();
            assertThat(text(line, "employeeId")).isEqualTo(EMPLOYEE);
            assertThat(line.get("message").asText()).contains("published");
        });

        String stored = jdbc.queryForObject(
                "select correlation_id from outbox where aggregate_id = ?",
                String.class,
                EMPLOYEE
        );
        assertThat(stored).isEqualTo(TRACE);
    }

    @Test
    void anInvalidCorrelationIdIsReplacedAndErrorsCarryIt() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER, "bad id");
        assertThat(CorrelationIds.resolve("bad id\ninjected")).doesNotContain("\n").doesNotContain("injected");
        ResponseEntity<String> response = restTemplate.exchange(
                "/employees/missing",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        String issued = response.getHeaders().getFirst(CorrelationIds.HEADER);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(issued).isNotBlank().isNotEqualTo("bad id").doesNotContain(" ");
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.get("code").asText()).isEqualTo("UNAUTHORIZED");
        assertThat(body.get("message").asText()).isEqualTo("Authentication required");
        assertThat(body.get("correlationId").asText()).isEqualTo(issued);
        assertThat(response.getBody()).doesNotContain(PASSWORD);
    }

    @Test
    void serviceLogsStayFreeOfPersonalValues(CapturedOutput output) {
        offices.save(new OfficeEntity(UUID.randomUUID(), "log-direct", "BLR"));
        syncEvents.ingest(java.util.List.of(new SyncEvent(
                "log-direct",
                "emp-direct",
                1L,
                EventType.CREATE,
                new EmployeeFields(NAME, EMAIL, PHONE, "M1", "BLR", PICKUP, null, "DAY")
        )));
        assertThat(output).doesNotContain(NAME, EMAIL, PHONE, PICKUP, PASSWORD);
    }

    private List<JsonNode> jsonLines(CapturedOutput output, int outBefore, int errBefore) throws Exception {
        String fresh = output.getOut().substring(Math.min(outBefore, output.getOut().length()))
                + output.getErr().substring(Math.min(errBefore, output.getErr().length()));
        List<JsonNode> lines = new ArrayList<>();
        for (String line : fresh.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("WARNING:") || trimmed.startsWith("Mockito")
                    || trimmed.startsWith("OpenJDK")) {
                continue;
            }
            assertThat(trimmed).as("log line is JSON").startsWith("{");
            lines.add(objectMapper.readTree(trimmed));
        }
        return lines;
    }

    private static String text(JsonNode line, String name) {
        JsonNode node = line.get(name);
        if ((node == null || node.isNull()) && line.has("mdc")) {
            node = line.get("mdc").get(name);
        }
        if (node == null || node.isNull()) {
            node = line.get("_" + name);
        }
        return node == null || node.isNull() ? null : node.asText();
    }
}
