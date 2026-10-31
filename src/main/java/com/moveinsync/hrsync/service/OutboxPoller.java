package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.OutboxSettings;
import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.domain.PublishResult;
import com.moveinsync.hrsync.metrics.SyncMeters;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    // One index probe per employee, using idx_outbox_pending_aggregate, instead of a
    // nested-loop search for LIMIT rows. A poll that asks for more heads than exist
    // must not scan every pending row.
    private static final String HEADS = """
            with recursive heads as (
              (
                select tenant_id, aggregate_id, id, next_attempt_at, 0 as ready
                from outbox
                where status = 'PENDING'
                order by tenant_id, aggregate_id, id
                limit 1
              )
              union all
              select n.tenant_id, n.aggregate_id, n.id, n.next_attempt_at,
                     h.ready + case when h.next_attempt_at <= ? then 1 else 0 end
              from heads h
              join lateral (
                select tenant_id, aggregate_id, id, next_attempt_at
                from outbox
                where status = 'PENDING'
                  and (tenant_id, aggregate_id) > (h.tenant_id, h.aggregate_id)
                order by tenant_id, aggregate_id, id
                limit 1
              ) n on true
              where h.ready < ?
            )
            select tenant_id, aggregate_id, id
            from heads
            where next_attempt_at <= ?
              and not exists (
                select 1
                from outbox blocked
                where blocked.tenant_id = heads.tenant_id
                  and blocked.aggregate_id = heads.aggregate_id
                  and blocked.id < heads.id
                  and blocked.status = 'FAILED'
              )
            order by id
            """;

    private static final String LOCK_HEAD = """
            select id
            from outbox
            where id = ? and tenant_id = ? and status = 'PENDING'
            for update skip locked
            """;

    private static final String LOCK_RUN = """
            select id, tenant_id, aggregate_id, event_type, payload::text as payload, attempts,
                   created_at, correlation_id, next_attempt_at
            from outbox
            where tenant_id = ? and aggregate_id = ? and status = 'PENDING' and id >= ?
            order by id
            limit ?
            for update
            """;

    private static final String BLOCKED = """
            select 1
            from outbox
            where tenant_id = ? and aggregate_id = ? and id < ?
              and status = 'FAILED'
            limit 1
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventPublisher publisher;
    private final OutboxPublishGuard guard;
    private final OutboxSettings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SyncMeters meters;
    private final PublishCircuit circuit;
    private final ExecutorService publishers;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicInteger inFlight = new AtomicInteger();

    public OutboxPoller(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            EventPublisher publisher,
            OutboxPublishGuard guard,
            OutboxSettings settings,
            ObjectMapper objectMapper,
            Clock clock,
            SyncMeters meters,
            PublishCircuit circuit
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
        this.guard = guard;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meters = meters;
        this.circuit = circuit;
        int threads = Math.max(1, settings.getPublisherThreads());
        this.publishers = threads == 1 ? null : Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "outbox-publish");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        accepting.set(false);
        long deadline = System.nanoTime() + Math.max(0, settings.getShutdownTimeout().toNanos());
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (publishers != null) {
            publishers.shutdown();
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    if (!publishers.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                        publishers.shutdownNow();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    publishers.shutdownNow();
                }
            }
        }
    }

    public int tick() {
        if (!accepting.get()) {
            return 0;
        }
        long start = System.nanoTime();
        long budget = Math.max(0, settings.getTickBudget().toNanos());
        int delivered = 0;
        while (accepting.get()) {
            Batch batch = claimAndPublish(settings.getBatchSize());
            delivered += batch.delivered;
            if (!batch.more || System.nanoTime() - start >= budget) {
                break;
            }
        }
        recordRate(delivered, start);
        return delivered;
    }

    public int poll() {
        return poll(settings.getBatchSize());
    }

    public int poll(int batchSize) {
        long start = System.nanoTime();
        Batch batch = claimAndPublish(batchSize);
        recordRate(batch.delivered, start);
        return batch.delivered;
    }

    private void recordRate(int delivered, long startNanos) {
        double seconds = (System.nanoTime() - startNanos) / 1_000_000_000.0;
        if (seconds > 0) {
            meters.publishRate(delivered / seconds);
        }
    }

    private Batch claimAndPublish(int batchSize) {
        if (!accepting.get()) {
            return new Batch(0, false);
        }
        inFlight.incrementAndGet();
        try {
            if (!accepting.get()) {
                return new Batch(0, false);
            }
            return claimAndPublishHolding(batchSize);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private Batch claimAndPublishHolding(int batchSize) {
        int limit = circuit.claimLimit(Math.max(1, batchSize));
        if (limit <= 0) {
            return new Batch(0, false);
        }
        List<Head> heads = findHeads(limit);
        int claimed = 0;
        int delivered = 0;
        boolean more = false;
        if (!heads.isEmpty()) {
            int parallelism = publishers == null ? 1 : Math.max(1, settings.getPublisherThreads());
            if (limit == 1) {
                parallelism = 1;
            }
            int slots = Math.min(parallelism, heads.size());
            int share = Math.max(1, (limit + slots - 1) / slots);
            List<List<Head>> groups = new ArrayList<>();
            for (int slot = 0; slot < slots; slot++) {
                groups.add(new ArrayList<>());
            }
            for (int index = 0; index < heads.size(); index++) {
                groups.get(index % slots).add(heads.get(index));
            }
            List<Work> wave = new ArrayList<>();
            for (int index = 0; index < slots; index++) {
                if (!groups.get(index).isEmpty()) {
                    wave.add(new Work(groups.get(index), share));
                }
            }
            for (Run run : runWave(wave)) {
                claimed += run.claimed;
                delivered += run.delivered;
                if (run.claimed >= run.requested) {
                    more = true;
                }
            }
        }
        if (claimed >= limit) {
            more = true;
        }
        meters.claimSize(claimed);
        return new Batch(delivered, more);
    }

    private List<Run> runWave(List<Work> wave) {
        if (publishers == null || wave.size() == 1) {
            List<Run> runs = new ArrayList<>(wave.size());
            for (Work work : wave) {
                runs.add(publishRun(work));
            }
            return runs;
        }
        List<Future<Run>> futures = new ArrayList<>(wave.size());
        for (Work work : wave) {
            futures.add(publishers.submit(() -> publishRun(work)));
        }
        List<Run> runs = new ArrayList<>(wave.size());
        RuntimeException failure = null;
        for (Future<Run> future : futures) {
            try {
                runs.add(future.get(2, TimeUnit.MINUTES));
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                if (cause instanceof Error error) {
                    throw error;
                }
                failure = cause instanceof RuntimeException runtime
                        ? runtime
                        : new IllegalStateException(cause);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failure = new IllegalStateException(exception);
            } catch (Exception exception) {
                failure = new IllegalStateException(exception);
            }
        }
        if (failure != null) {
            throw failure;
        }
        return runs;
    }

    private Run publishRun(Work work) {
        Run run = transactions.execute(status -> deliverBatch(work));
        if (run == null) {
            return new Run(work.limit, 0, 0);
        }
        meters.outboxPublished(run.delivered);
        return run;
    }

    private Run deliverBatch(Work work) {
        List<List<Claimed>> runs = new ArrayList<>();
        int claimed = 0;
        int perEmployee = Math.min(
                employeeCap(),
                Math.max(1, (work.limit + work.heads.size() - 1) / work.heads.size())
        );
        for (Head head : work.heads) {
            if (claimed >= work.limit) {
                break;
            }
            int room = Math.min(perEmployee, work.limit - claimed);
            List<Claimed> ready = lockReady(head, room);
            claimed += ready.size();
            if (!ready.isEmpty()) {
                runs.add(ready);
            }
        }
        int delivered = publishRounds(runs);
        return new Run(work.limit, claimed, delivered);
    }

    private List<Claimed> lockReady(Head head, int limit) {
        List<Long> locked = jdbc.query(LOCK_HEAD, (rs, row) -> rs.getLong("id"), head.id, head.tenantId);
        if (locked.isEmpty()) {
            return List.of();
        }
        List<Integer> blocked = jdbc.query(
                BLOCKED,
                (rs, row) -> 1,
                head.tenantId,
                head.aggregateId,
                head.id
        );
        if (!blocked.isEmpty()) {
            return List.of();
        }
        List<Claimed> rows = jdbc.query(
                LOCK_RUN,
                (rs, row) -> mapClaim(rs),
                head.tenantId,
                head.aggregateId,
                head.id,
                limit
        );
        if (rows.isEmpty() || rows.get(0).notice().eventId() != head.id) {
            return List.of();
        }
        List<Claimed> ready = new ArrayList<>();
        Instant now = clock.instant();
        for (Claimed row : rows) {
            if (row.readyAt != null && row.readyAt.isAfter(now)) {
                break;
            }
            ready.add(row);
        }
        return ready;
    }

    private int publishRounds(List<List<Claimed>> runs) {
        if (runs.isEmpty()) {
            return 0;
        }
        int rounds = 0;
        for (List<Claimed> run : runs) {
            rounds = Math.max(rounds, run.size());
        }
        boolean[] skip = new boolean[runs.size()];
        int delivered = 0;
        boolean brokerDown = false;
        for (int round = 0; round < rounds && !brokerDown; round++) {
            List<Claimed> slice = new ArrayList<>();
            List<Integer> owners = new ArrayList<>();
            for (int index = 0; index < runs.size(); index++) {
                if (skip[index] || runs.get(index).size() <= round) {
                    continue;
                }
                Claimed claimedRow = runs.get(index).get(round);
                guard.beforePublish(claimedRow.notice());
                slice.add(claimedRow);
                owners.add(index);
            }
            if (slice.isEmpty()) {
                continue;
            }
            List<ChangeNotice> notices = new ArrayList<>(slice.size());
            for (Claimed claimedRow : slice) {
                notices.add(claimedRow.notice());
            }
            List<PublishResult> results = publisher.publishRound(notices);
            Map<Long, PublishResult> byId = new HashMap<>();
            for (PublishResult result : results) {
                byId.put(result.notice().eventId(), result);
            }
            Map<String, List<Long>> ackedByTenant = new HashMap<>();
            for (int index = 0; index < slice.size(); index++) {
                Claimed claimedRow = slice.get(index);
                PublishResult result = byId.get(claimedRow.notice().eventId());
                if (result != null && result.acked()) {
                    ackedByTenant.computeIfAbsent(claimedRow.notice().tenantId(), key -> new ArrayList<>())
                            .add(claimedRow.notice().eventId());
                    delivered++;
                    continue;
                }
                RuntimeException failure = result == null
                        ? new IllegalStateException("publish round dropped an event")
                        : result.failure();
                if (PublishFailures.isTransient(failure)) {
                    meters.publishTransient();
                    brokerDown = true;
                    continue;
                }
                if (PublishFailures.isKafka(failure)) {
                    meters.publishPermanent();
                }
                recordFailure(claimedRow, failure);
                meters.outboxRetried();
                skip[owners.get(index)] = true;
            }
            for (Map.Entry<String, List<Long>> entry : ackedByTenant.entrySet()) {
                markPublished(entry.getKey(), entry.getValue());
            }
            if (brokerDown) {
                circuit.transientFailure();
            }
        }
        if (!brokerDown) {
            circuit.success();
        }
        return delivered;
    }

    private void markPublished(String tenantId, List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Long[] boxed = ids.toArray(Long[]::new);
        int updated = jdbc.update(
                """
                        update outbox
                        set published_at = ?, status = 'PUBLISHED', next_attempt_at = null, failure_reason = null
                        where tenant_id = ? and id = any (?)
                        """,
                (PreparedStatement statement) -> {
                    statement.setTimestamp(1, Timestamp.from(clock.instant()));
                    statement.setString(2, tenantId);
                    statement.setArray(3, statement.getConnection().createArrayOf("bigint", boxed));
                }
        );
        if (updated != ids.size()) {
            throw new IllegalStateException("Published update matched " + updated + " of " + ids.size() + " rows");
        }
    }

    private List<Head> findHeads(int limit) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.query(
                HEADS,
                (rs, row) -> new Head(rs.getLong("id"), rs.getString("tenant_id"), rs.getString("aggregate_id")),
                now,
                limit,
                now
        );
    }

    private int employeeCap() {
        return Math.max(1, settings.getEmployeeCap());
    }

    private void recordFailure(Claimed claimedRow, RuntimeException exception) {
        int attempts = claimedRow.attempts() + 1;
        ChangeNotice notice = claimedRow.notice();
        if (attempts >= settings.getMaxAttempts()) {
            jdbc.update(
                    """
                            update outbox
                            set attempts = ?, status = 'FAILED', next_attempt_at = null, failure_reason = ?
                            where id = ? and tenant_id = ?
                            """,
                    attempts,
                    reasonCode(exception),
                    notice.eventId(),
                    notice.tenantId()
            );
            log.warn(
                    "outbox publish exhausted eventId={} tenantId={} aggregateId={} attempts={} error={}",
                    notice.eventId(),
                    notice.tenantId(),
                    notice.aggregateId(),
                    attempts,
                    exception.getClass().getSimpleName()
            );
            return;
        }
        Duration delay = retryDelay(attempts);
        jdbc.update(
                """
                update outbox
                set attempts = ?, next_attempt_at = ?, failure_reason = ?
                where id = ? and tenant_id = ?
                """,
                attempts,
                Timestamp.from(clock.instant().plus(delay)),
                reasonCode(exception),
                notice.eventId(),
                notice.tenantId()
        );
        log.warn(
                "outbox publish failed eventId={} tenantId={} aggregateId={} attempt={} retryInMs={} error={}",
                notice.eventId(),
                notice.tenantId(),
                notice.aggregateId(),
                attempts,
                delay.toMillis(),
                exception.getClass().getSimpleName()
        );
    }

    private static String reasonCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return "FAILURE";
        }
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    private Duration retryDelay(int attempt) {
        long base = settings.getRetryBase().toMillis();
        long cap = settings.getRetryMaxDelay().toMillis();
        if (base <= 0 || cap <= 0 || attempt <= 0) {
            return Duration.ZERO;
        }
        long delay = base;
        for (int i = 1; i < attempt; i++) {
            if (delay >= cap) {
                delay = cap;
                break;
            }
            delay = Math.min(cap, delay * 2);
        }
        if (cap > 0) {
            delay = Math.min(delay, cap);
        }
        if (delay <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(delay));
    }

    private Claimed mapClaim(ResultSet rs) throws SQLException {
        Timestamp readyAt = rs.getTimestamp("next_attempt_at");
        return new Claimed(
                mapNotice(rs),
                rs.getInt("attempts"),
                rs.getString("correlation_id"),
                readyAt == null ? null : readyAt.toInstant()
        );
    }

    private ChangeNotice mapNotice(ResultSet rs) throws SQLException {
        try {
            JsonNode payload = objectMapper.readTree(rs.getString("payload"));
            List<String> changedFields = new ArrayList<>();
            for (JsonNode field : payload.get("changedFields")) {
                changedFields.add(field.asText());
            }
            return new ChangeNotice(
                    rs.getLong("id"),
                    rs.getString("tenant_id"),
                    rs.getString("aggregate_id"),
                    rs.getString("event_type"),
                    changedFields,
                    payload.path("sourceVersion").asLong(0),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("correlation_id")
            );
        } catch (Exception exception) {
            throw new SQLException("Unreadable outbox payload for id " + rs.getLong("id"), exception);
        }
    }

    private record Head(long id, String tenantId, String aggregateId) {
    }

    private record Work(List<Head> heads, int limit) {
    }

    private record Run(int requested, int claimed, int delivered) {
    }

    private record Batch(int delivered, boolean more) {
    }

    private record Claimed(ChangeNotice notice, int attempts, String correlationId, Instant readyAt) {
    }
}
