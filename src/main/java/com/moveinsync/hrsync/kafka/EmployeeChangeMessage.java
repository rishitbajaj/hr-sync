package com.moveinsync.hrsync.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.domain.ChangeNotice;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EmployeeChangeMessage(
        long eventId,
        String tenantId,
        String employeeId,
        String eventType,
        List<String> changedFields,
        long sourceVersion,
        long sequence,
        Instant occurredAt,
        int schemaVersion
) {
    public static final int SCHEMA_VERSION = 1;

    public static EmployeeChangeMessage from(ChangeNotice notice) {
        return new EmployeeChangeMessage(
                notice.eventId(),
                notice.tenantId(),
                notice.aggregateId(),
                notice.eventType(),
                notice.changedFields(),
                notice.sourceVersion(),
                notice.eventId(),
                notice.createdAt(),
                SCHEMA_VERSION
        );
    }

    public static String key(ChangeNotice notice) {
        return notice.tenantId() + ":" + notice.aggregateId();
    }
}
