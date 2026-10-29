package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.domain.IngestResult;

import java.util.List;

public record SyncBatchResponse(List<SyncRecordResponse> results) {

    public static SyncBatchResponse from(List<IngestResult> results) {
        return new SyncBatchResponse(results.stream()
                .map(result -> new SyncRecordResponse(
                        result.tenantId(),
                        result.employeeId(),
                        result.sourceVersion(),
                        result.outcome(),
                        result.reasonCode()
                ))
                .toList());
    }
}
