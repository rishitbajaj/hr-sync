package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.domain.IngestResult;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SyncBatchResponse(List<SyncRecordResponse> results, String correlationId) {

    public static SyncBatchResponse from(List<IngestResult> results) {
        return from(results, null);
    }

    public static SyncBatchResponse from(List<IngestResult> results, String correlationId) {
        return new SyncBatchResponse(results.stream()
                .map(result -> new SyncRecordResponse(
                        result.tenantId(),
                        result.employeeId(),
                        result.sourceVersion(),
                        result.outcome(),
                        result.reasonCode()
                ))
                .toList(), correlationId);
    }
}
