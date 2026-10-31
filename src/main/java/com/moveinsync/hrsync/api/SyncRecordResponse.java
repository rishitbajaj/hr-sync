package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncRecordResponse(
        @Schema(example = "tenant-a") String tenantId,
        @Schema(example = "employee-1") String employeeId,
        @Schema(example = "1") Long sourceVersion,
        Outcome outcome,
        @Schema(description = "Omitted when outcome is APPLIED.") ReasonCode reasonCode
) {
}
