package com.moveinsync.hrsync.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pending depth, oldest pending age, and failed depth are sampled on a schedule.
 * A Prometheus scrape reads the last sample. A failed sample leaves the previous values in place.
 */
@Component
public class OutboxMetrics {

    public static final String PENDING_DEPTH =
            "select count(*) from outbox where status = 'PENDING'";

    public static final String OLDEST_PENDING_SECONDS =
            "select coalesce((select extract(epoch from (clock_timestamp() - created_at)) from outbox where status = 'PENDING' order by created_at limit 1), 0)";

    public static final String FAILED_DEPTH =
            "select count(*) from outbox where status = 'FAILED'";

    private final JdbcTemplate jdbc;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestPendingSeconds = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("outbox.pending", pending, AtomicLong::get).register(registry);
        Gauge.builder("outbox.oldest.pending.seconds", oldestPendingSeconds, AtomicLong::get).register(registry);
        Gauge.builder("outbox.failed", failed, AtomicLong::get).register(registry);
    }

    public void sample() {
        long pendingCount = query(PENDING_DEPTH);
        long oldest = query(OLDEST_PENDING_SECONDS);
        long failedCount = query(FAILED_DEPTH);
        pending.set(pendingCount);
        oldestPendingSeconds.set(oldest);
        failed.set(failedCount);
    }

    private long query(String sql) {
        Number value = jdbc.queryForObject(sql, Number.class);
        if (value == null) {
            throw new IllegalStateException("Outbox gauge query returned no value");
        }
        return value.longValue();
    }
}
