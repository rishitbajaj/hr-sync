package com.moveinsync.hrsync.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.hrsync.metrics.SyncMeters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PositiveCacheTest {

    @Test
    void aHitSkipsTheLoaderUntilTheTtlExpires() {
        ManualTicker ticker = new ManualTicker();
        PositiveCache<String> cache = cache(Duration.ofSeconds(10), ticker);
        AtomicInteger loads = new AtomicInteger();
        CacheKey key = new CacheKey("tenant-a", "emp-1");

        assertThat(cache.get(key, () -> {
            loads.incrementAndGet();
            return Optional.of("DAY");
        })).contains("DAY");
        assertThat(cache.get(key, () -> {
            loads.incrementAndGet();
            return Optional.of("NIGHT");
        })).contains("DAY");
        assertThat(loads).hasValue(1);

        ticker.advance(Duration.ofSeconds(11));
        assertThat(cache.get(key, () -> {
            loads.incrementAndGet();
            return Optional.of("NIGHT");
        })).contains("NIGHT");
        assertThat(loads).hasValue(2);
    }

    @Test
    void aMissIsNotRemembered() {
        PositiveCache<String> cache = cache(Duration.ofSeconds(60), new ManualTicker());
        AtomicInteger loads = new AtomicInteger();
        CacheKey key = new CacheKey("tenant-a", "missing");
        assertThat(cache.get(key, () -> {
            loads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();
        assertThat(cache.get(key, () -> {
            loads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();
        assertThat(loads).hasValue(2);
    }

    private static PositiveCache<String> cache(Duration ttl, Ticker ticker) {
        return new PositiveCache<>(100, ttl, ticker, new SyncMeters(new SimpleMeterRegistry()), "employees");
    }

    private static final class ManualTicker implements Ticker {
        private long nanos;

        void advance(Duration duration) {
            nanos += duration.toNanos();
        }

        @Override
        public long read() {
            return nanos;
        }
    }
}
