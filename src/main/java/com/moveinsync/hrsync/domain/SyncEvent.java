package com.moveinsync.hrsync.domain;

public record SyncEvent(
        String tenantId,
        String employeeId,
        Long sourceVersion,
        EventType type,
        EmployeeFields fields
) {
    public SyncEvent {
        tenantId = Text.trimToNull(tenantId);
        employeeId = Text.trimToNull(employeeId);
    }

    public EmployeeFields fieldsOrEmpty() {
        return fields == null ? EmployeeFields.empty() : fields;
    }

    @Override
    public String toString() {
        return "SyncEvent[tenantId=%s, employeeId=%s, sourceVersion=%s, type=%s]".formatted(
                tenantId, employeeId, sourceVersion, type);
    }
}
