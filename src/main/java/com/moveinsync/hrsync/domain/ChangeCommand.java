package com.moveinsync.hrsync.domain;

public record ChangeCommand(
        String tenantId,
        String employeeId,
        long version,
        EventType type,
        FieldChannel channel,
        EmployeeFields fields
) {
    public static ChangeCommand from(SyncEvent event, FieldChannel channel) {
        return new ChangeCommand(
                event.tenantId(),
                event.employeeId(),
                event.sourceVersion(),
                event.type(),
                channel,
                event.fieldsOrEmpty()
        );
    }

    @Override
    public String toString() {
        return "ChangeCommand[tenantId=%s, employeeId=%s, version=%s, type=%s, channel=%s]".formatted(
                tenantId, employeeId, version, type, channel);
    }
}
