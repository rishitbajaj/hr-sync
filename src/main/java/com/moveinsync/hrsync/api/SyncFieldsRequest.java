package com.moveinsync.hrsync.api;

import io.swagger.v3.oas.annotations.media.Schema;

public record SyncFieldsRequest(
        @Schema(example = "Row One") String name,
        @Schema(example = "row-one@example.com") String email,
        @Schema(example = "+15555550100") String phone,
        @Schema(example = "manager-1") String manager,
        @Schema(example = "OFFICE-1") String officeCode,
        @Schema(example = "point-1") String pickupPoint,
        @Schema(example = "none") String specialAssistance,
        @Schema(example = "DAY") String shift
) {
    @Override
    public String toString() {
        return "SyncFieldsRequest[redacted]";
    }
}
