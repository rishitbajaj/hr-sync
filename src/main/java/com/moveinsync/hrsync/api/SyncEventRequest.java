package com.moveinsync.hrsync.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record SyncEventRequest(
        @Schema(example = "tenant-a") String tenantId,
        @Schema(example = "employee-1") String employeeId,
        @Schema(example = "1") Long sourceVersion,
        @Schema(example = "CREATE", allowableValues = {"CREATE", "UPDATE", "DEACTIVATE"}) String type,
        SyncFieldsRequest fields
) {
    @Override
    public String toString() {
        return "SyncEventRequest[tenantId=%s, employeeId=%s, sourceVersion=%s, type=%s]".formatted(
                tenantId, employeeId, sourceVersion, type);
    }
}
