package com.moveinsync.hrsync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.api.SyncBatchResponse;
import com.moveinsync.hrsync.domain.BatchIngestor;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.EventValidator;
import com.moveinsync.hrsync.domain.HrEmployee;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BatchIngestTest {

    @Test
    void batchOfTenAcceptsSevenAndRejectsThreeWithoutPersonalData() throws Exception {
        InMemoryChangeStore store = new InMemoryChangeStore();
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            List<IngestResult> results = service(store).ingest(SampleHrBatch.domainEvents());

            assertThat(results).extracting(IngestResult::outcome).containsExactly(
                    Outcome.APPLIED,
                    Outcome.REJECTED,
                    Outcome.APPLIED,
                    Outcome.APPLIED,
                    Outcome.REJECTED,
                    Outcome.APPLIED,
                    Outcome.APPLIED,
                    Outcome.APPLIED,
                    Outcome.REJECTED,
                    Outcome.APPLIED
            );
            assertThat(results).filteredOn(result -> result.outcome() == Outcome.APPLIED).hasSize(7);
            assertThat(results).filteredOn(result -> result.outcome() == Outcome.REJECTED).hasSize(3);
            assertThat(reason(results, "emp-02")).isEqualTo(ReasonCode.INVALID_PHONE);
            assertThat(reason(results, "emp-05")).isEqualTo(ReasonCode.UNKNOWN_OFFICE);
            assertThat(reason(results, "emp-09")).isEqualTo(ReasonCode.MISSING_REQUIRED_FIELD);
            assertThat(store.find(SampleHrBatch.TENANT, "emp-02")).isEmpty();
            assertThat(store.find(SampleHrBatch.TENANT, "emp-05")).isEmpty();
            assertThat(store.find(SampleHrBatch.TENANT, "emp-09")).isEmpty();
            assertThat(store.find(SampleHrBatch.TENANT, "emp-10")).isPresent();

            String response = new ObjectMapper().writeValueAsString(SyncBatchResponse.from(results));
            SampleHrBatch.assertNoPersonalData(response);
            SampleHrBatch.assertNoPersonalData(results.toString());
            assertCapturedLogsOmitSensitiveValues(logs, 10);
        } finally {
            detach(logs);
        }
    }

    @Test
    void storeFailureDoesNotAbortTheBatchOrLogPersonalData() {
        String leakedEmail = "hidden.person@leak.example";
        String leakedPhone = "+15559999999";
        InMemoryChangeStore store = new InMemoryChangeStore();
        store.failWhen(command -> {
            if ("emp-bad".equals(command.employeeId())) {
                throw new IllegalStateException("db rejected " + leakedEmail + " " + leakedPhone);
            }
        });
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            List<IngestResult> results = service(store).ingest(List.of(
                    create("emp-bad", 1, leakedEmail, leakedPhone),
                    create("emp-ok", 2, "ok.person@leak.example", "+15550000022")
            ));

            assertThat(results).extracting(IngestResult::outcome)
                    .containsExactly(Outcome.REJECTED, Outcome.APPLIED);
            assertThat(results.get(0).reasonCode()).isEqualTo(ReasonCode.APPLY_FAILED);
            assertThat(store.find(SampleHrBatch.TENANT, "emp-ok")).isPresent();
            logs.list.forEach(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain(leakedEmail).doesNotContain(leakedPhone);
                assertThat(event.getThrowableProxy()).isNull();
            });
        } finally {
            detach(logs);
        }
    }

    @Test
    void malformedEmailIsRejectedAndDoesNotAppearInLogs() {
        String badEmail = "not-an-email";
        String home = "88 Hidden Home Road";
        InMemoryChangeStore store = new InMemoryChangeStore();
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            List<IngestResult> results = service(store).ingest(List.of(
                    create("emp-bad-mail", 1, badEmail, "+15550000031"),
                    new SyncEvent(
                            SampleHrBatch.TENANT,
                            "emp-after-mail",
                            2L,
                            EventType.CREATE,
                            new EmployeeFields("Nia", "nia@leak.example", "+15550000032", "M1", SampleHrBatch.OFFICE, home, null)
                    )
            ));

            assertThat(results).extracting(IngestResult::reasonCode)
                    .containsExactly(ReasonCode.INVALID_EMAIL, null);
            assertThat(results.get(1).outcome()).isEqualTo(Outcome.APPLIED);
            assertThat(store.find(SampleHrBatch.TENANT, "emp-bad-mail")).isEmpty();
            assertThat(store.find(SampleHrBatch.TENANT, "emp-after-mail")).isPresent();
            String logged = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (left, right) -> left + "\n" + right);
            assertThat(logged).doesNotContain(badEmail).doesNotContain(home).doesNotContain("+1555").doesNotContain("@leak.example");
            logs.list.forEach(event -> assertThat(event.getThrowableProxy()).isNull());
        } finally {
            detach(logs);
        }
    }

    @Test
    void hrUpdateDoesNotOverwriteTransportFields() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        store.put(HrEmployee.rehydrate(
                UUID.randomUUID(),
                SampleHrBatch.TENANT,
                "emp-01",
                "Ada",
                "emp-01@leak.example",
                "+15550000001",
                "M1",
                SampleHrBatch.OFFICE,
                "Gate 4",
                "wheelchair",
                1,
                4,
                EmployeeStatus.ACTIVE,
                Instant.EPOCH
        ));

        service(store).ingest(List.of(new SyncEvent(
                SampleHrBatch.TENANT,
                "emp-01",
                2L,
                EventType.UPDATE,
                new EmployeeFields("Ada Lovelace", null, null, null, null, SampleHrBatch.HOME, "should-not-stick")
        )));

        HrEmployee stored = store.find(SampleHrBatch.TENANT, "emp-01").orElseThrow();
        assertThat(stored.name()).isEqualTo("Ada Lovelace");
        assertThat(stored.email()).isEqualTo("emp-01@leak.example");
        assertThat(stored.phone()).isEqualTo("+15550000001");
        assertThat(stored.pickupPoint()).isEqualTo("Gate 4");
        assertThat(stored.specialAssistance()).isEqualTo("wheelchair");
        assertThat(stored.hrVersion()).isEqualTo(2);
        assertThat(stored.transportVersion()).isEqualTo(4);
    }

    private static ReasonCode reason(List<IngestResult> results, String employeeId) {
        return results.stream()
                .filter(result -> employeeId.equals(result.employeeId()))
                .findFirst()
                .orElseThrow()
                .reasonCode();
    }

    private static SyncEvent create(String employeeId, long version, String email, String phone) {
        return new SyncEvent(
                SampleHrBatch.TENANT,
                employeeId,
                version,
                EventType.CREATE,
                new EmployeeFields("Ada", email, phone, "M1", SampleHrBatch.OFFICE, null, null)
        );
    }

    private static SyncEventService service(InMemoryChangeStore store) {
        OfficeDirectory offices = (tenantId, officeCode) ->
                SampleHrBatch.TENANT.equals(tenantId) && SampleHrBatch.OFFICE.equals(officeCode);
        return new SyncEventService(new BatchIngestor(new EventValidator(), offices, store));
    }

    private static void assertCapturedLogsOmitSensitiveValues(ListAppender<ILoggingEvent> logs, int ingestLines) {
        assertThat(logs.list.stream().filter(BatchIngestTest::isIngestLine).toList()).hasSize(ingestLines);
        logs.list.forEach(event -> {
            SampleHrBatch.assertNoPersonalData(event.getFormattedMessage());
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private static boolean isIngestLine(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        return message.startsWith("applied ") || message.startsWith("rejected ");
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
    }
}
