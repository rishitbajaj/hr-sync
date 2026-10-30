package com.moveinsync.hrsync.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class BatchIngestor {

    private final EventValidator validator;
    private final OfficeDirectory offices;
    private final ChangeStore changes;
    private final Predicate<Throwable> infrastructure;

    public BatchIngestor(
            EventValidator validator,
            OfficeDirectory offices,
            ChangeStore changes,
            Predicate<Throwable> infrastructure
    ) {
        this.validator = validator;
        this.offices = offices;
        this.changes = changes;
        this.infrastructure = infrastructure;
    }

    public List<IngestResult> ingest(List<SyncEvent> events, FieldChannel channel) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        IngestResult[] results = new IngestResult[events.size()];
        List<IndexedEvent> ready = new ArrayList<>();
        for (int index = 0; index < events.size(); index++) {
            SyncEvent event = events.get(index);
            Optional<ReasonCode> problem;
            try {
                problem = validator.validate(event, offices, channel);
            } catch (RuntimeException exception) {
                if (infrastructure.test(exception)) {
                    return stop(results, events, index);
                }
                throw exception;
            }
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
            try {
                results[item.index()] = changes.apply(ChangeCommand.from(item.event(), channel));
            } catch (RuntimeException exception) {
                if (infrastructure.test(exception)) {
                    return stop(results, events, item.index());
                }
                results[item.index()] = rejected(item.event(), ReasonCode.APPLY_FAILED);
            }
        }
        return List.of(results);
    }

    private static List<IngestResult> stop(IngestResult[] results, List<SyncEvent> events, int failedIndex) {
        results[failedIndex] = retryable(events.get(failedIndex), ReasonCode.DATABASE_UNAVAILABLE);
        for (int index = 0; index < events.size(); index++) {
            if (results[index] == null) {
                results[index] = retryable(events.get(index), ReasonCode.NOT_PROCESSED);
            }
        }
        return List.of(results);
    }

    private static IngestResult retryable(SyncEvent event, ReasonCode reasonCode) {
        if (event == null) {
            return IngestResult.retryable(null, null, null, reasonCode);
        }
        return IngestResult.retryable(event.tenantId(), event.employeeId(), event.sourceVersion(), reasonCode);
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
