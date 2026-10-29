package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncRecordResponse(
        String tenantId,
        String employeeId,
        Long sourceVersion,
        Outcome outcome,
        ReasonCode reasonCode
) {
}
