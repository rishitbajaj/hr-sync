package com.moveinsync.hrsync.domain;

public record IngestResult(
        String tenantId,
        String employeeId,
        Long sourceVersion,
        Outcome outcome,
        ReasonCode reasonCode
) {
    public static IngestResult applied(String tenantId, String employeeId, Long sourceVersion) {
        return new IngestResult(tenantId, employeeId, sourceVersion, Outcome.APPLIED, null);
    }

    public static IngestResult rejected(String tenantId, String employeeId, Long sourceVersion, ReasonCode reasonCode) {
        return new IngestResult(tenantId, employeeId, sourceVersion, Outcome.REJECTED, reasonCode);
    }

    public static IngestResult ignored(String tenantId, String employeeId, Long sourceVersion, ReasonCode reasonCode) {
        return new IngestResult(tenantId, employeeId, sourceVersion, Outcome.IGNORED, reasonCode);
    }

    public static IngestResult retryable(String tenantId, String employeeId, Long sourceVersion, ReasonCode reasonCode) {
        return new IngestResult(tenantId, employeeId, sourceVersion, Outcome.RETRYABLE_ERROR, reasonCode);
    }
}
