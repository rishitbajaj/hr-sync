package com.moveinsync.hrsync.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class BatchIngestor {

    private final EventValidator validator;
    private final OfficeDirectory offices;
    private final ChangeStore changes;

    public BatchIngestor(EventValidator validator, OfficeDirectory offices, ChangeStore changes) {
        this.validator = validator;
        this.offices = offices;
        this.changes = changes;
    }

    public List<IngestResult> ingest(List<SyncEvent> events, FieldChannel channel) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        IngestResult[] results = new IngestResult[events.size()];
        List<IndexedEvent> ready = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            SyncEvent event = events.get(index);
            Optional<ReasonCode> problem = validator.validate(event, offices, channel);
            if (problem.isPresent()) {
                results[index] = rejected(event, problem.get());
            } else {
                ready.add(new IndexedEvent(index, event));
            }
        }
        ready.sort(Comparator
                .comparing((IndexedEvent item) -> item.event().tenantId())
                .thenComparing(item -> item.event().employeeId())
                .thenComparingLong(item -> item.event().sourceVersion())
                .thenComparingInt(IndexedEvent::index));
        for (IndexedEvent item : ready) {
            results[item.index()] = apply(item.event(), channel);
        }
        return List.of(results);
    }

    private IngestResult apply(SyncEvent event, FieldChannel channel) {
        try {
            return changes.apply(ChangeCommand.from(event, channel));
        } catch (Exception ex) {
            return rejected(event, ReasonCode.APPLY_FAILED);
        }
    }

    private static IngestResult rejected(SyncEvent event, ReasonCode reasonCode) {
        if (event == null) {
            return IngestResult.rejected(null, null, null, reasonCode);
        }
        return IngestResult.rejected(event.tenantId(), event.employeeId(), event.sourceVersion(), reasonCode);
    }

    private record IndexedEvent(int index, SyncEvent event) {
    }
}
