package com.moveinsync.hrsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.OutboxSettings;
import com.moveinsync.hrsync.domain.ChangeNotice;
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
import com.moveinsync.hrsync.service.OutboxPublishGuard;
import com.moveinsync.hrsync.service.PersistGate;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class OutboxPollerTest {

    private static final String HYD = "HYD";
    private static final String SECRET_NAME = "Nola Shore";
    private static final String SECRET_EMAIL = "nola.shore@leak.example";
    private static final String SECRET_PHONE = "+15551230000";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OfficeRepository offices;

    @Autowired
    private LoggingEventPublisher publisher;

    @Autowired
    private OutboxPoller poller;

    @Autowired
    private PersistGate persistGate;

    @Autowired
    private OutboxPublishGuard publishGuard;

    @Autowired
    private OutboxSettings settings;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    @BeforeEach
    void seedOffices() {
        saveOffice(SampleHrBatch.OFFICE);
        saveOffice(HYD);
        publisher.setListener(null);
        persistGate.failNext(null);
        publishGuard.setBeforePublish(null);
        while (poller.poll() > 0) {
            // publish rows left by earlier tests so this test owns the queue
        }
        publisher.clear();
    }

    @AfterEach
    void clearGates() {
        publisher.clear();
        publisher.setListener(null);
        persistGate.failNext(null);
        publishGuard.setBeforePublish(null);
    }

    @Test
    void appliedChangeWritesOneOutboxRowAndNoOpsWriteNone() {
        assertThat(indexDefinition()).containsIgnoringCase("where ((status)::text = 'PENDING'::text)");

        IngestResult created = apply(create("emp-box", 1, "Ada"));
        IngestResult renamed = apply(rename("emp-box", 3, "Ada Lovelace"));
        assertThat(created.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(renamed.outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(outboxCount("emp-box")).isEqualTo(2);
        List<Long> ids = outboxIds("emp-box");
        assertThat(ids).hasSize(2);
        assertThat(ids.get(0)).isLessThan(ids.get(1));

        IngestResult duplicate = apply(create("emp-box", 1, "Ada"));
        IngestResult stale = apply(rename("emp-box", 2, "Older"));
        IngestResult rejected = apply(new SyncEvent(
                SampleHrBatch.TENANT,
                "emp-bad-phone",
                1L,
                EventType.CREATE,
                new EmployeeFields("Bea", "bea@leak.example", "nope", "M1", SampleHrBatch.OFFICE, null, null, null)
        ));

        assertThat(duplicate.reasonCode()).isEqualTo(ReasonCode.DUPLICATE);
        assertThat(stale.reasonCode()).isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(rejected.reasonCode()).isEqualTo(ReasonCode.INVALID_PHONE);
        assertThat(outboxCount("emp-box")).isEqualTo(2);
        assertThat(outboxCount("emp-bad-phone")).isZero();
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        persistGate.failNext(new IllegalStateException("forced rollback"));

        IngestResult result = apply(create("emp-rollback", 1, "Rae"));

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.APPLY_FAILED);
        assertThat(outboxCount("emp-rollback")).isZero();
        assertThat(count("employee", "emp-rollback")).isZero();
        assertThat(count("processed_event", "emp-rollback")).isZero();
    }

    @Test
    void twoPollersPublishEachRowOnceAndSkipNone() throws Exception {
        for (int index = 0; index < 8; index++) {
            assertThat(apply(create("emp-p" + index, 1, "Person " + index)).outcome()).isEqualTo(Outcome.APPLIED);
        }
        publisher.clear();
        List<Long> storedIds = jdbc.queryForList(
                "select id from outbox where aggregate_id like 'emp-p%' order by id",
                Long.class
        );
        assertThat(storedIds).hasSize(8);

        OutboxPoller first = newPoller();
        OutboxPoller second = newPoller();
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        publisher.setListener(notice -> {
            if (calls.incrementAndGet() == 1) {
                holding.countDown();
                try {
                    if (!release.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("second poller did not arrive");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> firstResult = pool.submit(() -> first.poll(1));
            assertThat(holding.await(15, TimeUnit.SECONDS)).isTrue();
            Future<Integer> secondResult = pool.submit(() -> second.poll(1));
            assertThat(secondResult.get(15, TimeUnit.SECONDS)).isEqualTo(1);
            release.countDown();
            assertThat(firstResult.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        publisher.setListener(null);
        while (unpublishedLike("emp-p%") > 0) {
            int delivered = first.poll(1) + second.poll(1);
            assertThat(delivered).isPositive();
        }

        List<Long> publishedIds = publisher.published().stream().map(ChangeNotice::eventId).toList();
        assertThat(publishedIds).doesNotHaveDuplicates();
        assertThat(publishedIds).containsExactlyInAnyOrderElementsOf(storedIds);
        assertThat(unpublishedLike("emp-p%")).isZero();
    }

    @Test
    void publishOrderFollowsPerEmployeeVersionOrder() {
        apply(create("emp-order", 1, "Ada"));
        apply(create("emp-other", 1, "Bea"));
        apply(rename("emp-order", 2, "Ada Lovelace"));
        apply(move("emp-order", 3, HYD));
        publisher.clear();

        while (poller.poll() > 0) {
            // drain unpublished heads in id order
        }

        List<ChangeNotice> published = publisher.published();
        assertThat(published).extracting(ChangeNotice::aggregateId)
                .containsExactly("emp-order", "emp-other", "emp-order", "emp-order");
        List<ChangeNotice> employee = published.stream()
                .filter(notice -> "emp-order".equals(notice.aggregateId()))
                .toList();
        assertThat(employee.get(0).changedFields()).contains("status", "officeCode", "shift");
        assertThat(employee.get(1).changedFields()).containsExactly("name");
        assertThat(employee.get(2).changedFields()).containsExactly("officeCode");
        assertThat(employee.get(0).eventId()).isLessThan(employee.get(1).eventId());
        assertThat(employee.get(1).eventId()).isLessThan(employee.get(2).eventId());
    }

    @Test
    void twoSlowPollersPreservePerEmployeeOrderWithoutGaps() throws Exception {
        int versions = 10;
        for (int version = 1; version <= versions; version++) {
            if (version == 1) {
                assertThat(apply(create("emp-seq-a", 1, "A1")).outcome()).isEqualTo(Outcome.APPLIED);
                assertThat(apply(create("emp-seq-b", 1, "B1")).outcome()).isEqualTo(Outcome.APPLIED);
            } else {
                assertThat(apply(rename("emp-seq-a", version, "A" + version)).outcome()).isEqualTo(Outcome.APPLIED);
                assertThat(apply(rename("emp-seq-b", version, "B" + version)).outcome()).isEqualTo(Outcome.APPLIED);
            }
        }
        publisher.clear();
        List<Long> stored = jdbc.queryForList(
                """
                        select id from outbox
                        where aggregate_id in ('emp-seq-a', 'emp-seq-b')
                        order by id
                        """,
                Long.class
        );
        assertThat(stored).hasSize(versions * 2);

        OutboxPoller first = newPoller();
        OutboxPoller second = newPoller();
        CountDownLatch bothPublishing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger held = new AtomicInteger();
        publisher.setListener(notice -> {
            if (held.incrementAndGet() <= 2) {
                bothPublishing.countDown();
                await(release);
                return;
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted");
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> left = pool.submit(() -> drain(first, "emp-seq-%"));
            Future<?> right = pool.submit(() -> drain(second, "emp-seq-%"));
            assertThat(bothPublishing.await(20, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            left.get(60, TimeUnit.SECONDS);
            right.get(60, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        List<ChangeNotice> published = publisher.published().stream()
                .filter(notice -> notice.aggregateId().startsWith("emp-seq-"))
                .toList();
        assertThat(published).extracting(ChangeNotice::eventId).doesNotHaveDuplicates();
        assertThat(published).extracting(ChangeNotice::eventId).containsExactlyInAnyOrderElementsOf(stored);
        for (String employeeId : List.of("emp-seq-a", "emp-seq-b")) {
            List<Long> order = published.stream()
                    .filter(notice -> employeeId.equals(notice.aggregateId()))
                    .map(ChangeNotice::eventId)
                    .toList();
            List<Long> expected = jdbc.queryForList(
                    "select id from outbox where aggregate_id = ? order by id",
                    Long.class,
                    employeeId
            );
            assertThat(order).containsExactlyElementsOf(expected);
        }
    }

    @Test
    void aLaterVersionStaysUnclaimedWhileItsEarlierVersionIsInFlight() throws Exception {
        apply(create("emp-inflight", 1, "Ada"));
        apply(rename("emp-inflight", 2, "Ada Lovelace"));
        List<Long> ids = outboxIds("emp-inflight");
        assertThat(ids).hasSize(2);
        long earlier = ids.get(0);
        long later = ids.get(1);

        CountDownLatch versionOneHeld = new CountDownLatch(1);
        CountDownLatch releaseVersionOne = new CountDownLatch(1);
        publisher.setListener(notice -> {
            if (notice.eventId() == earlier) {
                versionOneHeld.countDown();
                await(releaseVersionOne);
            }
        });

        OutboxPoller first = newPoller();
        OutboxPoller second = newPoller();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> held = pool.submit(() -> first.poll(1));
            assertThat(versionOneHeld.await(20, TimeUnit.SECONDS)).isTrue();

            assertThat(second.poll(1)).isZero();
            assertThat(publisher.published()).extracting(ChangeNotice::eventId).containsExactly(earlier);
            assertThat(statusOf(later)).isEqualTo("PENDING");

            releaseVersionOne.countDown();
            assertThat(held.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(statusOf(earlier)).isEqualTo("PUBLISHED");

            assertThat(second.poll(1)).isEqualTo(1);
            assertThat(publisher.published()).extracting(ChangeNotice::eventId).containsExactly(earlier, later);
            assertThat(statusOf(later)).isEqualTo("PUBLISHED");
        } finally {
            releaseVersionOne.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void publishFailsTwiceThenSucceeds() {
        apply(create("emp-retry", 1, "Ada"));
        AtomicInteger failures = new AtomicInteger();
        publisher.setListener(notice -> {
            if (failures.getAndIncrement() < 2) {
                throw new IllegalStateException("publish down");
            }
        });

        assertThat(poller.poll()).isZero();
        assertThat(attempts("emp-retry")).isEqualTo(1);
        assertThat(outboxStatus("emp-retry")).isEqualTo("PENDING");
        assertThat(poller.poll()).isZero();
        assertThat(attempts("emp-retry")).isEqualTo(2);
        assertThat(outboxStatus("emp-retry")).isEqualTo("PENDING");

        assertThat(poller.poll()).isEqualTo(1);
        assertThat(outboxStatus("emp-retry")).isEqualTo("PUBLISHED");
        assertThat(attempts("emp-retry")).isEqualTo(2);
        assertThat(unpublished("emp-retry")).isZero();
    }

    @Test
    void exhaustedPublishEndsFailedWithoutBlockingOtherEmployees() {
        apply(create("emp-stuck", 1, "Ada"));
        publisher.setListener(notice -> {
            throw new IllegalStateException("publish down");
        });
        for (int attempt = 0; attempt < settings.getMaxAttempts(); attempt++) {
            assertThat(poller.poll()).isZero();
        }

        assertThat(outboxStatus("emp-stuck")).isEqualTo("FAILED");
        assertThat(attempts("emp-stuck")).isEqualTo(settings.getMaxAttempts());
        assertThat(poller.poll()).isZero();
        assertThat(outboxStatus("emp-stuck")).isEqualTo("FAILED");

        publisher.setListener(null);
        apply(create("emp-ok", 1, "Bea"));
        apply(rename("emp-stuck", 2, "Ada Two"));
        assertThat(poller.poll()).isEqualTo(2);

        assertThat(statuses("emp-stuck")).containsExactly("FAILED", "PUBLISHED");
        assertThat(statuses("emp-ok")).containsExactly("PUBLISHED");
        assertThat(unpublished("emp-ok")).isZero();
    }

    @Test
    void crashAfterCommitBeforePublishStillDeliversOnceToADedupingConsumer() {
        apply(create("emp-crash", 1, "Ada"));
        assertThat(unpublished("emp-crash")).isEqualTo(1);

        publishGuard.setBeforePublish(notice -> {
            throw new IllegalStateException("crash before publish");
        });
        assertThatThrownBy(() -> poller.poll()).isInstanceOf(IllegalStateException.class);
        assertThat(publisher.published()).isEmpty();
        assertThat(unpublished("emp-crash")).isEqualTo(1);
        assertThat(attempts("emp-crash")).isZero();

        DedupingConsumer consumer = new DedupingConsumer();
        AtomicBoolean crashAfterSideEffect = new AtomicBoolean(true);
        publishGuard.setBeforePublish(null);
        publisher.setListener(notice -> {
            consumer.accept(notice);
            if (crashAfterSideEffect.getAndSet(false)) {
                throw new IllegalStateException("crash after side effect");
            }
        });
        assertThat(newPoller().poll()).isZero();
        assertThat(consumer.effects()).isEqualTo(1);
        assertThat(unpublished("emp-crash")).isEqualTo(1);
        assertThat(attempts("emp-crash")).isEqualTo(1);
        assertThat(outboxStatus("emp-crash")).isEqualTo("PENDING");

        publisher.setListener(consumer::accept);
        assertThat(newPoller().poll()).isEqualTo(1);
        assertThat(consumer.effects()).isEqualTo(1);
        assertThat(publisher.published()).hasSize(2);
        assertThat(publisher.published()).extracting(ChangeNotice::eventId).containsOnly(publisher.published().get(0).eventId());
        assertThat(unpublished("emp-crash")).isZero();
    }

    @Test
    void placeholderCreationEmitsNoEventUntilActivation() {
        assertThat(apply(rename("emp-hold", 5, "Later")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(apply(rename("emp-hold", 6, "Later Still")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(employeeStatus("emp-hold")).isEqualTo("PLACEHOLDER");
        assertThat(outboxCount("emp-hold")).isZero();
        assertThat(count("processed_event", "emp-hold")).isEqualTo(2);

        assertThat(apply(create("emp-hold", 3, "Early")).reasonCode()).isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(outboxCount("emp-hold")).isZero();

        assertThat(apply(create("emp-hold", 8, "Real")).outcome()).isEqualTo(Outcome.APPLIED);
        assertThat(employeeStatus("emp-hold")).isEqualTo("ACTIVE");
        assertThat(outboxCount("emp-hold")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select event_type from outbox where aggregate_id = ?",
                String.class,
                "emp-hold"
        )).isEqualTo("CREATE");
        assertThat(jdbc.queryForObject(
                "select payload::text from outbox where aggregate_id = ?",
                String.class,
                "emp-hold"
        )).contains("status");
    }

    @Test
    void outboxPayloadOmitsPersonalValues() {
        apply(new SyncEvent(
                SampleHrBatch.TENANT,
                "emp-secret",
                1L,
                EventType.CREATE,
                new EmployeeFields(
                        SECRET_NAME,
                        SECRET_EMAIL,
                        SECRET_PHONE,
                        "M1",
                        SampleHrBatch.OFFICE,
                        null,
                        null,
                        "DAY"
                )
        ));

        String payload = jdbc.queryForObject(
                "select payload::text from outbox where aggregate_id = ?",
                String.class,
                "emp-secret"
        );
        assertThat(payload).contains("emp-secret").contains("changedFields");
        assertThat(payload)
                .doesNotContain(SECRET_NAME)
                .doesNotContain(SECRET_EMAIL)
                .doesNotContain(SECRET_PHONE)
                .doesNotContain("Nola")
                .doesNotContain("Shore")
                .doesNotContain("leak.example");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other poller");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }

    private void drain(OutboxPoller instance, String employeePattern) {
        int idle = 0;
        while (unpublishedLike(employeePattern) > 0) {
            if (instance.poll(1) == 0) {
                idle++;
                if (idle > 2000) {
                    throw new IllegalStateException("poller made no progress");
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted");
                }
            } else {
                idle = 0;
            }
        }
    }

    private OutboxPoller newPoller() {
        return new OutboxPoller(
                jdbc,
                transactionManager,
                publisher,
                publishGuard,
                settings,
                objectMapper,
                clock
        );
    }

    private IngestResult apply(SyncEvent event) {
        return syncEvents.ingest(List.of(event)).get(0);
    }

    private long outboxCount(String employeeId) {
        return count("outbox", employeeId, "aggregate_id");
    }

    private long count(String table, String employeeId) {
        return count(table, employeeId, "employee_id");
    }

    private long count(String table, String employeeId, String column) {
        Long count = jdbc.queryForObject(
                "select count(*) from " + table + " where tenant_id = ? and " + column + " = ?",
                Long.class,
                SampleHrBatch.TENANT,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private List<Long> outboxIds(String employeeId) {
        return jdbc.queryForList(
                "select id from outbox where tenant_id = ? and aggregate_id = ? order by id",
                Long.class,
                SampleHrBatch.TENANT,
                employeeId
        );
    }

    private int attempts(String employeeId) {
        Integer count = jdbc.queryForObject(
                "select attempts from outbox where tenant_id = ? and aggregate_id = ?",
                Integer.class,
                SampleHrBatch.TENANT,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private String employeeStatus(String employeeId) {
        return jdbc.queryForObject(
                "select status from employee where tenant_id = ? and employee_id = ?",
                String.class,
                SampleHrBatch.TENANT,
                employeeId
        );
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from outbox where id = ?", String.class, id);
    }

    private String outboxStatus(String employeeId) {
        return jdbc.queryForObject(
                "select status from outbox where tenant_id = ? and aggregate_id = ?",
                String.class,
                SampleHrBatch.TENANT,
                employeeId
        );
    }

    private List<String> statuses(String employeeId) {
        return jdbc.queryForList(
                "select status from outbox where tenant_id = ? and aggregate_id = ? order by id",
                String.class,
                SampleHrBatch.TENANT,
                employeeId
        );
    }

    private long unpublished(String employeeId) {
        Long count = jdbc.queryForObject(
                """
                        select count(*) from outbox
                        where tenant_id = ? and aggregate_id = ? and published_at is null
                        """,
                Long.class,
                SampleHrBatch.TENANT,
                employeeId
        );
        return count == null ? 0 : count;
    }

    private long unpublishedLike(String employeePattern) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where aggregate_id like ? and published_at is null",
                Long.class,
                employeePattern
        );
        return count == null ? 0 : count;
    }

    private String indexDefinition() {
        return jdbc.queryForObject(
                """
                        select indexdef from pg_indexes
                        where schemaname = 'public' and indexname = 'idx_outbox_pending_aggregate'
                        """,
                String.class
        );
    }

    private void saveOffice(String code) {
        if (!offices.existsByTenantIdAndCode(SampleHrBatch.TENANT, code)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), SampleHrBatch.TENANT, code));
        }
    }

    private static SyncEvent create(String employeeId, long version, String name) {
        return new SyncEvent(
                SampleHrBatch.TENANT,
                employeeId,
                version,
                EventType.CREATE,
                new EmployeeFields(name, employeeId + "@leak.example", "+15551230000", "M1", SampleHrBatch.OFFICE, null, null, "DAY")
        );
    }

    private static SyncEvent rename(String employeeId, long version, String name) {
        return new SyncEvent(
                SampleHrBatch.TENANT,
                employeeId,
                version,
                EventType.UPDATE,
                new EmployeeFields(name, null, null, null, null, null, null, null)
        );
    }

    private static final class DedupingConsumer {
        private final Set<Long> seen = ConcurrentHashMap.newKeySet();
        private final AtomicInteger effects = new AtomicInteger();

        void accept(ChangeNotice notice) {
            if (seen.add(notice.eventId())) {
                effects.incrementAndGet();
            }
        }

        int effects() {
            return effects.get();
        }
    }

    private static SyncEvent move(String employeeId, long version, String office) {
        return new SyncEvent(
                SampleHrBatch.TENANT,
                employeeId,
                version,
                EventType.UPDATE,
                new EmployeeFields(null, null, null, null, office, null, null, null)
        );
    }
}
