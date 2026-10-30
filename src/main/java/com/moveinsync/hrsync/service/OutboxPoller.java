package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.OutboxSettings;
import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.metrics.SyncMeters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    private static final String CLAIM = """
            select o.id, o.tenant_id, o.aggregate_id, o.event_type, o.payload::text as payload, o.attempts,
                   o.created_at
            from outbox o
            where o.status = 'PENDING'
              and o.next_attempt_at <= ?
              and not exists (
                  select 1
                  from outbox earlier
                  where earlier.tenant_id = o.tenant_id
                    and earlier.aggregate_id = o.aggregate_id
                    and earlier.id < o.id
                    and (
                        earlier.status = 'PENDING'
                        or (earlier.status = 'FAILED' and earlier.event_type = 'EMPLOYEE_DEACTIVATED')
                    )
              )
            order by o.id
            for update of o skip locked
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DeactivationEventConsumer deactivations;
    private final EventPublisher publisher;
    private final OutboxPublishGuard guard;
    private final OutboxSettings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SyncMeters meters;

    public OutboxPoller(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            DeactivationEventConsumer deactivations,
            EventPublisher publisher,
            OutboxPublishGuard guard,
            OutboxSettings settings,
            ObjectMapper objectMapper,
            Clock clock,
            SyncMeters meters
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.deactivations = deactivations;
        this.publisher = publisher;
        this.guard = guard;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meters = meters;
    }

    public int poll() {
        return poll(settings.getBatchSize());
    }

    public int poll(int batchSize) {
        Integer delivered = transactions.execute(status -> deliver(batchSize));
        return delivered == null ? 0 : delivered;
    }

    private int deliver(int batchSize) {
        // A hash anti-join would read every pending row before LIMIT. Nested-loop probes
        // use idx_outbox_pending_id and idx_outbox_pending_aggregate and stop after the batch.
        jdbc.execute("set local enable_hashjoin = off");
        jdbc.execute("set local enable_mergejoin = off");
        List<Claimed> claimed = jdbc.query(
                CLAIM,
                (rs, row) -> mapClaim(rs),
                Timestamp.from(clock.instant()),
                batchSize
        );
        int delivered = 0;
        for (Claimed claimedRow : claimed) {
            guard.beforePublish(claimedRow.notice());
            try {
                deactivations.handle(claimedRow.notice());
                publisher.publish(claimedRow.notice());
            } catch (RuntimeException exception) {
                recordFailure(claimedRow, exception);
                meters.outboxRetried();
                continue;
            }
            jdbc.update(
                    """
                            update outbox
                            set published_at = ?, status = 'PUBLISHED', next_attempt_at = null, failure_reason = null
                            where id = ? and tenant_id = ?
                            """,
                    Timestamp.from(clock.instant()),
                    claimedRow.notice().eventId(),
                    claimedRow.notice().tenantId()
            );
            meters.outboxPublished();
            delivered++;
        }
        return delivered;
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
        return new Claimed(mapNotice(rs), rs.getInt("attempts"));
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
                    rs.getTimestamp("created_at").toInstant()
            );
        } catch (Exception exception) {
            throw new SQLException("Unreadable outbox payload for id " + rs.getLong("id"), exception);
        }
    }

    private record Claimed(ChangeNotice notice, int attempts) {
    }
}
