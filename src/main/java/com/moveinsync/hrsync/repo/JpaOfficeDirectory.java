package com.moveinsync.hrsync.repo;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.hrsync.cache.CacheKey;
import com.moveinsync.hrsync.cache.PositiveCache;
import com.moveinsync.hrsync.config.CacheSettings;
import com.moveinsync.hrsync.domain.OfficeDirectory;
import com.moveinsync.hrsync.metrics.SyncMeters;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaOfficeDirectory implements OfficeDirectory {

    private final OfficeRepository offices;
    private final PositiveCache<Boolean> cache;

    public JpaOfficeDirectory(OfficeRepository offices, CacheSettings settings, Ticker ticker, SyncMeters meters) {
        this.offices = offices;
        this.cache = new PositiveCache<>(
                settings.getOfficeMaximumSize(),
                settings.getOfficeTtl(),
                ticker,
                meters,
                "offices"
        );
    }

    @Override
    public boolean exists(String tenantId, String officeCode) {
        if (tenantId == null || officeCode == null) {
            return offices.existsByTenantIdAndCode(tenantId, officeCode);
        }
        return cache.get(new CacheKey(tenantId, officeCode), () -> load(tenantId, officeCode)).orElse(false);
    }

    public long lookups() {
        return cache.lookups();
    }

    private Optional<Boolean> load(String tenantId, String officeCode) {
        if (offices.existsByTenantIdAndCode(tenantId, officeCode)) {
            return Optional.of(Boolean.TRUE);
        }
        return Optional.empty();
    }
}
