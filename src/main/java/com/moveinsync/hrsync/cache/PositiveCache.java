package com.moveinsync.hrsync.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.hrsync.metrics.SyncMeters;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

public final class PositiveCache<V> {

    private final Cache<CacheKey, V> cache;
    private final SyncMeters meters;
    private final String name;
    private long lookups;
    private long evictions;

    public PositiveCache(long maximumSize, Duration ttl, Ticker ticker, SyncMeters meters, String name) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterWrite(ttl)
                .ticker(ticker)
                .build();
        this.meters = meters;
        this.name = name;
    }

    public Optional<V> get(CacheKey key, Supplier<Optional<V>> loader) {
        lookups++;
        V cached = cache.getIfPresent(key);
        if (cached != null) {
            meters.cacheAccess(name, "hit");
            return Optional.of(cached);
        }
        meters.cacheAccess(name, "miss");
        Optional<V> loaded = loader.get();
        if (loaded != null && loaded.isPresent()) {
            cache.put(key, loaded.get());
        }
        return loaded == null ? Optional.empty() : loaded;
    }

    public void evict(CacheKey key) {
        cache.invalidate(key);
        evictions++;
    }

    public long lookups() {
        return lookups;
    }

    public long evictions() {
        return evictions;
    }
}
