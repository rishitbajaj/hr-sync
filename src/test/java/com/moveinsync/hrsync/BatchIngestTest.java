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
import com.moveinsync.hrsync.service.InfrastructureErrors;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

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
    void aConnectionFailureStopsTheBatchAndIsNotRejected() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        store.failWhen(command -> {
            if ("m-2".equals(command.employeeId())) {
                throw new CannotGetJdbcConnectionException("pool exhausted");
            }
        });

        List<IngestResult> results = ingestor(store).ingest(List.of(
                create("m-1", 1, "m1@leak.example", "+15550000041"),
                create("m-bad", 1, "bad@leak.example", "12345"),
                create("m-2", 1, "m2@leak.example", "+15550000042"),
                create("m-3", 1, "m3@leak.example", "+15550000043")
        ), com.moveinsync.hrsync.domain.FieldChannel.HR);

        assertThat(results).extracting(IngestResult::employeeId).containsExactly("m-1", "m-bad", "m-2", "m-3");
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(results.get(1).outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(results.get(1).reasonCode()).isEqualTo(ReasonCode.INVALID_PHONE);
        assertThat(results.get(2).outcome()).isEqualTo(Outcome.RETRYABLE_ERROR);
        assertThat(results.get(2).reasonCode()).isEqualTo(ReasonCode.DATABASE_UNAVAILABLE);
        assertThat(results.get(3).outcome()).isEqualTo(Outcome.RETRYABLE_ERROR);
        assertThat(results.get(3).reasonCode()).isEqualTo(ReasonCode.NOT_PROCESSED);
        assertThat(results).filteredOn(result -> result.outcome() == Outcome.REJECTED)
                .allSatisfy(result -> assertThat(result.reasonCode()).isNotIn(
                        ReasonCode.DATABASE_UNAVAILABLE, ReasonCode.NOT_PROCESSED));
        assertThat(store.find(SampleHrBatch.TENANT, "m-1")).isPresent();
        assertThat(store.find(SampleHrBatch.TENANT, "m-2")).isEmpty();
        assertThat(store.find(SampleHrBatch.TENANT, "m-3")).isEmpty();
    }

    @Test
    void aStatementTimeoutDuringValidationStopsBeforeAnyApply() {
        InMemoryChangeStore store = new InMemoryChangeStore();
        OfficeDirectory offices = (tenantId, officeCode) -> {
            throw new QueryTimeoutException("canceling statement due to statement timeout");
        };
        List<IngestResult> results = new BatchIngestor(new EventValidator(), offices, store, InfrastructureErrors::isInfrastructure)
                .ingest(List.of(
                        create("t-1", 1, "t1@leak.example", "+15550000051"),
                        create("t-2", 1, "t2@leak.example", "+15550000052")
                ), com.moveinsync.hrsync.domain.FieldChannel.HR);

        assertThat(results.get(0).outcome()).isEqualTo(Outcome.RETRYABLE_ERROR);
        assertThat(results.get(0).reasonCode()).isEqualTo(ReasonCode.DATABASE_UNAVAILABLE);
        assertThat(results.get(1).reasonCode()).isEqualTo(ReasonCode.NOT_PROCESSED);
        assertThat(results).noneMatch(result -> result.outcome() == Outcome.REJECTED);
        assertThat(store.find(SampleHrBatch.TENANT, "t-1")).isEmpty();
        assertThat(store.find(SampleHrBatch.TENANT, "t-2")).isEmpty();
    }

    @Test
    void aDataErrorIsNotInfrastructure() {
        assertThat(InfrastructureErrors.isInfrastructure(new DataIntegrityViolationException("value too long"))).isFalse();
        assertThat(InfrastructureErrors.isInfrastructure(new CannotGetJdbcConnectionException("down"))).isTrue();
        assertThat(InfrastructureErrors.isInfrastructure(new QueryTimeoutException("timeout"))).isTrue();
        assertThat(InfrastructureErrors.isInfrastructure(
                new DataIntegrityViolationException("wrapped", new java.sql.SQLException("cancel", "57014"))
        )).isTrue();
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
                            new EmployeeFields("Nia", "nia@leak.example", "+15550000032", "M1", SampleHrBatch.OFFICE, home, null, null)
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
                null,
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
                new EmployeeFields("Ada Lovelace", null, null, null, null, SampleHrBatch.HOME, "should-not-stick", null)
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
                new EmployeeFields("Ada", email, phone, "M1", SampleHrBatch.OFFICE, null, null, null)
        );
    }

    private static SyncEventService service(InMemoryChangeStore store) {
        return new SyncEventService(ingestor(store), new com.moveinsync.hrsync.metrics.SyncMeters(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    private static BatchIngestor ingestor(InMemoryChangeStore store) {
        OfficeDirectory offices = (tenantId, officeCode) ->
                SampleHrBatch.TENANT.equals(tenantId) && SampleHrBatch.OFFICE.equals(officeCode);
        return new BatchIngestor(new EventValidator(), offices, store, InfrastructureErrors::isInfrastructure);
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
