package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.config.OutboxSettings;
import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Component
public class OutboxPoller {

    private static final String CLAIM = """
            select o.id, o.tenant_id, o.aggregate_id, o.event_type, o.payload::text as payload
            from outbox o
            where o.published_at is null
              and not exists (
                  select 1
                  from outbox earlier
                  where earlier.published_at is null
                    and earlier.tenant_id = o.tenant_id
                    and earlier.aggregate_id = o.aggregate_id
                    and earlier.id < o.id
              )
            order by o.id
            for update of o skip locked
            limit ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final EventPublisher publisher;
    private final OutboxPublishGuard guard;
    private final OutboxSettings settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxPoller(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            EventPublisher publisher,
            OutboxPublishGuard guard,
            OutboxSettings settings,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.publisher = publisher;
        this.guard = guard;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public int poll() {
        return poll(settings.getBatchSize());
    }

    public int poll(int batchSize) {
        Integer delivered = transactions.execute(status -> deliver(batchSize));
        return delivered == null ? 0 : delivered;
    }

    private int deliver(int batchSize) {
        List<ChangeNotice> claimed = jdbc.query(CLAIM, (rs, row) -> mapNotice(rs), batchSize);
        for (ChangeNotice notice : claimed) {
            guard.beforePublish(notice);
            publisher.publish(notice);
            jdbc.update(
                    "update outbox set published_at = ? where id = ?",
                    Timestamp.from(clock.instant()),
                    notice.eventId()
            );
        }
        return claimed.size();
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
                    changedFields
            );
        } catch (Exception exception) {
            throw new SQLException("Unreadable outbox payload for id " + rs.getLong("id"), exception);
        }
    }
}
