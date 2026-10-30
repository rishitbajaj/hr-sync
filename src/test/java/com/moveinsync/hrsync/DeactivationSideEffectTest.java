package com.moveinsync.hrsync;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.booking.MockBookingService;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.LoggingEventPublisher;
import com.moveinsync.hrsync.service.OutboxPoller;
import com.moveinsync.hrsync.service.SyncEventService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Testcontainers
class DeactivationSideEffectTest {

    private static final String OFFICE = "BLR";
    private static final String SECRET_NAME = "Nola Shore";
    private static final String SECRET_EMAIL = "nola.shore@leak.example";
    private static final String SECRET_PHONE = "+15551230099";
    private static final String SECRET_PICKUP = "12 Secret Home Lane";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private MockBookingService bookings;

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private LoggingEventPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @BeforeEach
    void isolate() {
        bookings.failNext(0);
        bookings.timeoutNext(0);
        publisher.setListener(null);
        int spins = 0;
        while (poller.poll() > 0) {
            if (++spins > 100) {
                throw new IllegalStateException("outbox did not drain");
            }
        }
        bookings.reset();
        publisher.clear();
    }

    @Test
    void deactivationCancelsFutureBookingsOnly() {
        String tenant = "cab-future";
        hire(tenant, "emp-1", "Ada");
        Instant now = clock.instant();
        bookings.seedTrip(tenant, "trip-1", 3);
        bookings.seedBooking("past", tenant, "emp-1", "trip-1", now.minus(Duration.ofDays(2)));
        bookings.seedBooking("riding", tenant, "emp-1", "trip-1", now.minus(Duration.ofHours(1)));
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", now.plus(Duration.ofDays(1)));
        bookings.seedTrip(tenant, "trip-other", 1);
        bookings.seedBooking("other", tenant, "emp-2", "trip-other", now.plus(Duration.ofDays(1)));

        assertThat(deactivate(tenant, "emp-1", 2).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(poller.poll()).isEqualTo(1);

        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.isCancelled("past")).isFalse();
        assertThat(bookings.isCancelled("riding")).isFalse();
        assertThat(bookings.isCancelled("other")).isFalse();
        assertThat(bookings.occupied("trip-1")).isEqualTo(2);
        assertThat(bookings.occupied("trip-other")).isEqualTo(1);
    }

    @Test
    void seatsFreedEqualsCancelledBookings() {
        String tenant = "cab-seats";
        hire(tenant, "emp-1", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 2);
        bookings.seedBooking("a", tenant, "emp-1", "trip-1", start);
        bookings.seedBooking("b", tenant, "emp-1", "trip-1", start.plus(Duration.ofHours(2)));

        deactivate(tenant, "emp-1", 2);
        assertThat(poller.poll()).isEqualTo(1);

        assertThat(bookings.isCancelled("a")).isTrue();
        assertThat(bookings.isCancelled("b")).isTrue();
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(seatsFreed(tenant, "emp-1")).isEqualTo(2);
        assertThat(bookings.cancelCalls()).isEqualTo(1);
    }

    @Test
    void duplicateAndStaleDeactivationCancelOnce() {
        String tenant = "cab-once";
        hire(tenant, "emp-1", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);

        assertThat(deactivate(tenant, "emp-1", 3).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(deactivate(tenant, "emp-1", 3).reasonCode()).isEqualTo(ReasonCode.DUPLICATE);
        assertThat(deactivate(tenant, "emp-1", 2).reasonCode()).isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(poller.poll()).isEqualTo(1);
        assertThat(poller.poll()).isZero();

        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.cancelCalls()).isEqualTo(1);
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(effectRows(tenant, "emp-1")).isEqualTo(1);
    }

    @Test
    void bookingFailuresAreRetriedWithoutASecondCancellation() {
        String tenant = "cab-retry";
        hire(tenant, "emp-1", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);
        bookings.failNext(2);
        deactivate(tenant, "emp-1", 2);

        assertThat(poller.poll()).isZero();
        assertThat(bookings.isCancelled("later")).isFalse();
        assertThat(poller.poll()).isZero();
        assertThat(bookings.isCancelled("later")).isFalse();
        assertThat(outboxStatus(tenant, "emp-1")).isEqualTo("PENDING");

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(bookings.cancelCalls()).isEqualTo(3);
        assertThat(seatsFreed(tenant, "emp-1")).isEqualTo(1);
        assertThat(effectRows(tenant, "emp-1")).isEqualTo(1);
        assertThat(outboxStatus(tenant, "emp-1")).isEqualTo("PUBLISHED");
    }

    @Test
    void aBookingClientThatKeepsFailingLeavesOneFailedRowAndDoesNotBlockOthers() {
        String tenant = "cab-down";
        hire(tenant, "emp-stuck", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-stuck", "trip-1", start);
        bookings.failNext(Integer.MAX_VALUE);
        deactivate(tenant, "emp-stuck", 2);

        assertThat(poller.poll()).isZero();
        assertThat(poller.poll()).isZero();
        assertThat(poller.poll()).isZero();

        assertThat(outboxStatus(tenant, "emp-stuck")).isEqualTo("FAILED");
        assertThat(bookings.isCancelled("later")).isFalse();
        assertThat(effectRows(tenant, "emp-stuck")).isZero();
        assertThat(jdbc.queryForObject(
                """
                        select count(*) from outbox
                        where tenant_id = ? and aggregate_id = ? and status = 'FAILED' and event_type = 'EMPLOYEE_DEACTIVATED'
                        """,
                Long.class,
                tenant,
                "emp-stuck"
        )).isEqualTo(1L);

        bookings.failNext(0);
        hire(tenant, "emp-ok", "Bea");
        assertThat(outboxStatus(tenant, "emp-ok")).isEqualTo("PUBLISHED");
        assertThat(outboxStatus(tenant, "emp-stuck")).isEqualTo("FAILED");
        assertThat(bookings.isCancelled("later")).isFalse();
    }

    @Test
    void aCrashAfterCancellationDoesNotCancelAgainOnRedelivery() {
        String tenant = "cab-crash";
        hire(tenant, "emp-1", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);
        deactivate(tenant, "emp-1", 2);
        AtomicInteger crashes = new AtomicInteger();
        publisher.setListener(notice -> {
            if (crashes.getAndIncrement() == 0) {
                throw new IllegalStateException("crash after cancel");
            }
        });

        assertThat(poller.poll()).isZero();
        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(outboxStatus(tenant, "emp-1")).isEqualTo("PENDING");
        assertThat(effectRows(tenant, "emp-1")).isEqualTo(1);

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(bookings.cancelCalls()).isEqualTo(1);
        assertThat(seatsFreed(tenant, "emp-1")).isEqualTo(1);
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(outboxStatus(tenant, "emp-1")).isEqualTo("PUBLISHED");
    }

    @Test
    void anApprovedReconciliationDeactivationCancelsFutureBookings() throws Exception {
        String tenant = "cab-approve";
        hire(tenant, "emp-1", "Ada");
        hire(tenant, "emp-2", "Bea");
        Instant now = clock.instant();
        bookings.seedTrip(tenant, "trip-1", 3);
        bookings.seedBooking("keep-future", tenant, "emp-1", "trip-1", now.plus(Duration.ofDays(1)));
        bookings.seedBooking("drop-future", tenant, "emp-2", "trip-1", now.plus(Duration.ofDays(1)));
        bookings.seedBooking("drop-past", tenant, "emp-2", "trip-1", now.minus(Duration.ofDays(2)));

        HttpHeaders csvHeaders = new HttpHeaders();
        csvHeaders.setContentType(MediaType.parseMediaType("text/csv"));
        String csv = """
                tenantId,employeeId,sourceVersion,name,email,phone,managerId,officeCode,shift
                %s,emp-1,1,Ada,emp-1@example.com,+15550000001,M1,BLR,DAY
                """.formatted(tenant);
        ResponseEntity<String> started = restTemplate.postForEntity(
                "/reconciliation/runs?tenantId=" + tenant,
                new HttpEntity<>(csv, csvHeaders),
                String.class
        );
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode halted = objectMapper.readTree(started.getBody());
        assertThat(halted.get("status").asText()).isEqualTo("HALTED");

        ResponseEntity<String> approved = restTemplate.withBasicAuth("operator", "test-operator")
                .postForEntity("/reconciliation/runs/" + halted.get("id").asText() + "/approve", null, String.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(poller.poll()).isEqualTo(1);

        assertThat(bookings.isCancelled("drop-future")).isTrue();
        assertThat(bookings.isCancelled("drop-past")).isFalse();
        assertThat(bookings.isCancelled("keep-future")).isFalse();
        assertThat(seatsFreed(tenant, "emp-2")).isEqualTo(1);
        assertThat(bookings.occupied("trip-1")).isEqualTo(2);
    }

    @Test
    void deactivationLogsAndPayloadsOmitPersonalValues() {
        String tenant = "cab-quiet";
        saveOffice(tenant);
        assertThat(syncEvents.ingest(java.util.List.of(new SyncEvent(
                tenant,
                "emp-quiet",
                1L,
                EventType.CREATE,
                new EmployeeFields(SECRET_NAME, SECRET_EMAIL, SECRET_PHONE, "M1", OFFICE, SECRET_PICKUP, null, "DAY")
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(poller.poll()).isEqualTo(1);
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            deactivate(tenant, "emp-quiet", 2);
            assertThat(poller.poll()).isEqualTo(1);
            String payload = jdbc.queryForObject(
                    "select payload::text from outbox where tenant_id = ? and aggregate_id = ? and event_type = 'EMPLOYEE_DEACTIVATED'",
                    String.class,
                    tenant,
                    "emp-quiet"
            );
            assertThat(payload).contains("emp-quiet").contains("status");
            assertThat(payload).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE).doesNotContain(SECRET_PICKUP);
            String effect = jdbc.queryForObject(
                    "select cancelled_booking_ids::text from deactivation_effect where employee_id = ?",
                    String.class,
                    "emp-quiet"
            );
            assertThat(effect).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE).doesNotContain(SECRET_PICKUP);
            assertThat(logs.list).isNotEmpty();
            logs.list.forEach(event -> {
                String message = event.getFormattedMessage();
                assertThat(message).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE).doesNotContain(SECRET_PICKUP);
            });
        } finally {
            root.detachAppender(logs);
        }
    }

    @Test
    void aLaterCreateDoesNotRestoreCancelledBookings() {
        String tenant = "cab-rehire";
        hire(tenant, "emp-1", "Ada");
        Instant start = clock.instant().plus(Duration.ofDays(1));
        bookings.seedTrip(tenant, "trip-1", 1);
        bookings.seedBooking("later", tenant, "emp-1", "trip-1", start);
        deactivate(tenant, "emp-1", 2);
        assertThat(poller.poll()).isEqualTo(1);
        assertThat(bookings.isCancelled("later")).isTrue();

        assertThat(syncEvents.ingest(java.util.List.of(new SyncEvent(
                tenant,
                "emp-1",
                4L,
                EventType.CREATE,
                new EmployeeFields("Ada", "emp-1@example.com", "+15550000001", "M1", OFFICE, null, null, "DAY")
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(poller.poll()).isEqualTo(1);

        assertThat(bookings.isCancelled("later")).isTrue();
        assertThat(bookings.occupied("trip-1")).isZero();
        assertThat(bookings.cancelCalls()).isEqualTo(1);
    }

    @Test
    void demoProfileCanSeedAndInspectBookings() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> trip = restTemplate.postForEntity(
                "/demo/trips",
                new HttpEntity<>("{\"tenantId\":\"demo-co\",\"tripId\":\"trip-demo\",\"capacity\":2}", headers),
                String.class
        );
        assertThat(trip.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trip.getBody()).contains("trip-demo").doesNotContain(SECRET_NAME);

        String booking = """
                {"bookingId":"b-demo","tenantId":"demo-co","employeeId":"emp-demo","tripId":"trip-demo","startsAt":"2026-11-02T09:00:00Z"}
                """;
        ResponseEntity<String> seeded = restTemplate.postForEntity(
                "/demo/bookings",
                new HttpEntity<>(booking, headers),
                String.class
        );
        assertThat(seeded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(seeded.getBody()).contains("b-demo").doesNotContain("name").doesNotContain("email");

        ResponseEntity<String> board = restTemplate.getForEntity("/demo/bookings", String.class);
        assertThat(board.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(board.getBody()).contains("trip-demo").contains("b-demo").contains("\"occupied\":1");
        assertThat(board.getBody()).doesNotContain(SECRET_NAME).doesNotContain(SECRET_EMAIL).doesNotContain(SECRET_PHONE);
    }

    private void hire(String tenant, String employeeId, String name) {
        saveOffice(tenant);
        assertThat(syncEvents.ingest(java.util.List.of(new SyncEvent(
                tenant,
                employeeId,
                1L,
                EventType.CREATE,
                new EmployeeFields(name, employeeId + "@example.com", "+15550000001", "M1", OFFICE, null, null, "DAY")
        ))).get(0).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(poller.poll()).isEqualTo(1);
    }

    private IngestResult deactivate(String tenant, String employeeId, long version) {
        return syncEvents.ingest(java.util.List.of(new SyncEvent(
                tenant,
                employeeId,
                version,
                EventType.DEACTIVATE,
                EmployeeFields.empty()
        ))).get(0);
    }

    private void saveOffice(String tenant) {
        if (!offices.existsByTenantIdAndCode(tenant, OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), tenant, OFFICE));
        }
    }

    private int seatsFreed(String tenant, String employeeId) {
        Integer seats = jdbc.queryForObject(
                """
                        select coalesce(sum(seats_freed), 0) from deactivation_effect
                        where tenant_id = ? and employee_id = ?
                        """,
                Integer.class,
                tenant,
                employeeId
        );
        return seats == null ? 0 : seats;
    }

    private long effectRows(String tenant, String employeeId) {
        Long count = jdbc.queryForObject(
                "select count(*) from deactivation_effect where tenant_id = ? and employee_id = ?",
                Long.class,
                tenant,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private String outboxStatus(String tenant, String employeeId) {
        return jdbc.queryForObject(
                """
                        select status from outbox
                        where tenant_id = ? and aggregate_id = ?
                        order by id desc
                        limit 1
                        """,
                String.class,
                tenant,
                employeeId
        );
    }
}
