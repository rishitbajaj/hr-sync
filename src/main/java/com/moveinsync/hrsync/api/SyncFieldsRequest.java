package com.moveinsync.hrsync.api;

public record SyncFieldsRequest(
        String name,
        String email,
        String phone,
        String manager,
        String officeCode,
        String pickupPoint,
        String specialAssistance,
        String shift
) {
    @Override
    public String toString() {
        return "SyncFieldsRequest[redacted]";
    }
}
