package com.moveinsync.hrsync.cache;

public record CachedEmployee(
        String tenantId,
        String employeeId,
        String status,
        String officeCode,
        String shift
) {
}
