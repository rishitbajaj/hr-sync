package com.moveinsync.hrsync.api;

public record SyncEventRequest(
        String tenantId,
        String employeeId,
        Long sourceVersion,
        String type,
        SyncFieldsRequest fields
) {
    @Override
    public String toString() {
        return "SyncEventRequest[tenantId=%s, employeeId=%s, sourceVersion=%s, type=%s]".formatted(
                tenantId, employeeId, sourceVersion, type);
    }
}
