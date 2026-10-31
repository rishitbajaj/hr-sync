package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.config.KafkaSettings;
import com.moveinsync.hrsync.metrics.SyncMeters;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;

@Component
public class PublishCircuit {

    public static final int CLOSED = 0;
    public static final int HALF_OPEN = 1;
    public static final int OPEN = 2;

    private final Clock clock;
    private final KafkaSettings settings;
    private final SyncMeters meters;
    private int state = CLOSED;
    private Instant notBefore = Instant.EPOCH;
    private Instant openedAt = Instant.EPOCH;
    private long backoffMillis;
    private final IntSupplier stateGauge = this::state;
    private final DoubleSupplier openGauge = this::openSeconds;

    public PublishCircuit(Clock clock, KafkaSettings settings, SyncMeters meters) {
        this.clock = clock;
        this.settings = settings;
        this.meters = meters;
        meters.circuitState(stateGauge);
        meters.circuitOpenSeconds(openGauge);
    }

    public synchronized int claimLimit(int requested) {
        if (state == CLOSED) {
            return requested;
        }
        Instant now = clock.instant();
        if (now.isBefore(notBefore)) {
            return 0;
        }
        state = HALF_OPEN;
        return 1;
    }

    public synchronized void success() {
        if (state == CLOSED) {
            return;
        }
        recordUnavailable(clock.instant());
        state = CLOSED;
        backoffMillis = 0;
        notBefore = Instant.EPOCH;
    }

    public synchronized void transientFailure() {
        Instant now = clock.instant();
        if (state == CLOSED) {
            openedAt = now;
        }
        long cap = Math.max(0, settings.getCircuitMax().toMillis());
        long base = Math.max(0, settings.getCircuitBase().toMillis());
        long next = backoffMillis <= 0 ? base : Math.min(cap, backoffMillis * 2);
        if (cap > 0) {
            next = Math.min(next, cap);
        }
        backoffMillis = next;
        long delay = next <= 1 ? next : ThreadLocalRandom.current().nextLong(next / 2, next + 1);
        notBefore = now.plusMillis(delay);
        state = OPEN;
    }

    public synchronized void reset() {
        if (state != CLOSED) {
            recordUnavailable(clock.instant());
        }
        state = CLOSED;
        backoffMillis = 0;
        notBefore = Instant.EPOCH;
    }

    public synchronized int state() {
        return state;
    }

    private synchronized double openSeconds() {
        if (state == CLOSED) {
            return 0;
        }
        return Math.max(0, clock.instant().toEpochMilli() - openedAt.toEpochMilli()) / 1000.0;
    }

    private void recordUnavailable(Instant now) {
        long millis = Math.max(0, now.toEpochMilli() - openedAt.toEpochMilli());
        if (millis > 0) {
            meters.circuitUnavailable(millis / 1000.0);
        }
    }
}
