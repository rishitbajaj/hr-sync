package com.moveinsync.hrsync.cache;

import com.github.benmanes.caffeine.cache.Ticker;
import com.moveinsync.hrsync.config.CacheSettings;
import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.metrics.SyncMeters;
import com.moveinsync.hrsync.repo.EmployeeRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class EmployeeReads {

    private final EmployeeRepository employees;
    private final PositiveCache<CachedEmployee> cache;

    public EmployeeReads(
            EmployeeRepository employees,
            CacheSettings settings,
            Ticker ticker,
            SyncMeters meters
    ) {
        this.employees = employees;
        this.cache = new PositiveCache<>(
                settings.getEmployeeMaximumSize(),
                settings.getEmployeeTtl(),
                ticker,
                meters,
                "employees"
        );
    }

    public Optional<CachedEmployee> get(String tenantId, String employeeId) {
        return cache.get(new CacheKey(tenantId, employeeId), () -> load(tenantId, employeeId));
    }

    public void evict(String tenantId, String employeeId) {
        cache.evict(new CacheKey(tenantId, employeeId));
    }

    public long lookups() {
        return cache.lookups();
    }

    public long evictions() {
        return cache.evictions();
    }

    private Optional<CachedEmployee> load(String tenantId, String employeeId) {
        return employees.findVisibleByTenantIdAndEmployeeId(tenantId, employeeId)
                .filter(employee -> employee.getStatus() != EmployeeStatus.PLACEHOLDER)
                .map(employee -> {
                    var roster = employee.toDomain();
                    return new CachedEmployee(
                            roster.tenantId(),
                            roster.employeeId(),
                            roster.status().name(),
                            roster.officeCode(),
                            roster.shift()
                    );
                });
    }
}
