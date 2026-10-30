package com.moveinsync.hrsync.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;

@Service
public class OutboxOperatorService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OutboxOperatorService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public List<FailedOutboxRow> failed() {
        return jdbc.query(
                """
                        select id, aggregate_id, attempts, failure_reason
                        from outbox
                        where status = 'FAILED'
                        order by id
                        """,
                (rs, row) -> new FailedOutboxRow(
                        rs.getString("aggregate_id"),
                        rs.getLong("id"),
                        rs.getInt("attempts"),
                        rs.getString("failure_reason")
                )
        );
    }

    public void replay(long eventId) {
        int updated = jdbc.update(
                """
                        update outbox
                        set status = 'PENDING', attempts = 0, next_attempt_at = ?, failure_reason = null
                        where id = ? and status = 'FAILED'
                        """,
                Timestamp.from(clock.instant()),
                eventId
        );
        if (updated != 1) {
            throw missingOrConflict(eventId);
        }
    }

    public void discard(long eventId, String reason) {
        if (reason == null || reason.isBlank() || reason.trim().length() > 500) {
            throw new OutboxRejectedException("A reason is required");
        }
        String trimmed = reason.trim();
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.update(
                    """
                            update outbox
                            set status = 'DISCARDED', next_attempt_at = null
                            where id = ? and status = 'FAILED'
                            """,
                    eventId
            );
            if (updated != 1) {
                throw missingOrConflict(eventId);
            }
            jdbc.update(
                    """
                            insert into outbox_discard (event_id, reason, discarded_at)
                            values (?, ?, ?)
                            """,
                    eventId,
                    trimmed,
                    Timestamp.from(clock.instant())
            );
        });
    }

    private RuntimeException missingOrConflict(long eventId) {
        List<String> statuses = jdbc.query(
                "select status from outbox where id = ?",
                (rs, row) -> rs.getString(1),
                eventId
        );
        if (statuses.isEmpty()) {
            throw new OutboxNotFoundException("Outbox event was not found");
        }
        throw new OutboxConflictException("Only a failed event can be changed");
    }

    public record FailedOutboxRow(String employeeId, long eventId, int attempts, String reasonCode) {
    }
}
