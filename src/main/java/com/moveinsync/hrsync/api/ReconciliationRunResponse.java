package com.moveinsync.hrsync.api;

import java.util.UUID;

public record ReconciliationRunResponse(
        UUID id,
        String tenantId,
        String status,
        int unchanged,
        int toCreate,
        int toUpdate,
        int toDeactivate,
        int missingCount,
        int activeCount,
        int exportRowCount
) {
}
