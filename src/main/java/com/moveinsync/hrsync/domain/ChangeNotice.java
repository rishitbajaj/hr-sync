package com.moveinsync.hrsync.domain;

import java.time.Instant;
import java.util.List;

public record ChangeNotice(
        long eventId,
        String tenantId,
        String aggregateId,
        String eventType,
        List<String> changedFields,
        Instant createdAt
) {
    public ChangeNotice {
        changedFields = List.copyOf(changedFields);
    }
}
