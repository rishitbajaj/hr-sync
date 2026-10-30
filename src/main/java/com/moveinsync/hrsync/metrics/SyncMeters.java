package com.moveinsync.hrsync.metrics;

import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.IngestResult;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SyncMeters {

    private final MeterRegistry registry;
    private final Timer ingestDuration;
    private final Timer reconciliationDuration;
    private final DistributionSummary claimSize;
    private final AtomicLong publishRateMilli = new AtomicLong();

    public SyncMeters(MeterRegistry registry) {
        this.registry = registry;
        this.claimSize = DistributionSummary.builder("outbox.claim.size")
                .baseUnit("rows")
                .register(registry);
        Gauge.builder("outbox.publish.rate", publishRateMilli, value -> value.get() / 1000.0)
                .baseUnit("rows")
                .register(registry);
        this.ingestDuration = Timer.builder("ingest.duration")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.reconciliationDuration = Timer.builder("reconciliation.duration")
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public void recordIngest(IngestResult result, FieldChannel channel) {
        String reason = result.reasonCode() == null ? "none" : result.reasonCode().name();
        registry.counter(counterName(result), "reason", reason, "channel", channel.name()).increment();
    }

    public void recordIngestDuration(Duration duration) {
        ingestDuration.record(duration);
    }

    public void recordReconciliationDuration(Duration duration) {
        reconciliationDuration.record(duration);
    }

    public void reconciliationFinished(String status) {
        registry.counter("reconciliation.runs", "status", status).increment();
        if ("HALTED".equals(status)) {
            registry.counter("reconciliation.halted", "reason", "MISSING_RATIO_EXCEEDED").increment();
        }
    }

    public void outboxPublished(int count) {
        if (count > 0) {
            registry.counter("outbox.published").increment(count);
        }
    }

    public void claimSize(int rows) {
        if (rows > 0) {
            claimSize.record(rows);
        }
    }

    public void publishRate(double rowsPerSecond) {
        publishRateMilli.set(Math.round(rowsPerSecond * 1000.0));
    }

    public void outboxPublished() {
        outboxPublished(1);
    }

    public void outboxRetried() {
        registry.counter("outbox.retries").increment();
    }

    public void bookingsCancelled(int count) {
        if (count > 0) {
            registry.counter("deactivation.bookings.cancelled").increment(count);
        }
    }

    public void seatsFreed(int count) {
        if (count > 0) {
            registry.counter("deactivation.seats.freed").increment(count);
        }
    }

    public void deactivationFailed() {
        registry.counter("deactivation.effect.failures").increment();
    }

    public void loginFailed() {
        registry.counter("auth.login.failures").increment();
    }

    public void lockout() {
        registry.counter("auth.lockouts").increment();
    }

    public void tokenRejected() {
        registry.counter("auth.rejected.tokens").increment();
    }

    public void cacheAccess(String cache, String result) {
        registry.counter("cache.gets", "cache", cache, "result", result).increment();
    }

    private static String counterName(IngestResult result) {
        if (result.outcome() == Outcome.APPLIED) {
            return "ingest.applied";
        }
        if (result.outcome() == Outcome.IGNORED && result.reasonCode() == ReasonCode.STALE_IGNORED) {
            return "ingest.stale_ignored";
        }
        if (result.outcome() == Outcome.IGNORED && result.reasonCode() == ReasonCode.DUPLICATE) {
            return "ingest.duplicate";
        }
        return "ingest.rejected";
    }
}
