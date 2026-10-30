package com.moveinsync.hrsync.auth;

public record AuthenticatedCaller(
        String username,
        String tenantId,
        UserRole role,
        String employeeId
) {
}
