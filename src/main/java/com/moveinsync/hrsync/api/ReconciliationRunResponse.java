package com.moveinsync.hrsync.api;

import java.util.List;
import java.util.Map;
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
        int exportRowCount,
        Double missingRatio,
        Double threshold,
        String haltReason,
        int rejected,
        Map<String, Integer> rejectedByReason,
        List<Rejection> rejections
) {
}
