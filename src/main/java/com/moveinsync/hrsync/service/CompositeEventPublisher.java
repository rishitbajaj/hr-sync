package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.domain.PublishResult;
import com.moveinsync.hrsync.observability.MdcScope;

import java.util.ArrayList;
import java.util.List;

public class CompositeEventPublisher implements EventPublisher {

    private final DeactivationEventConsumer effects;
    private final EventPublisher downstream;

    public CompositeEventPublisher(DeactivationEventConsumer effects, EventPublisher downstream) {
        this.effects = effects;
        this.downstream = downstream;
    }

    @Override
    public void publish(ChangeNotice notice) {
        effects.handle(notice);
        downstream.publish(notice);
    }

    @Override
    public List<PublishResult> publishRound(List<ChangeNotice> notices) {
        List<PublishResult> results = new ArrayList<>(notices.size());
        List<ChangeNotice> ready = new ArrayList<>(notices.size());
        for (ChangeNotice notice : notices) {
            try (MdcScope ignored = scope(notice)) {
                effects.handle(notice);
                ready.add(notice);
            } catch (RuntimeException exception) {
                results.add(new PublishResult(notice, exception));
            }
        }
        if (!ready.isEmpty()) {
            results.addAll(downstream.publishRound(ready));
        }
        return results;
    }

    private static MdcScope scope(ChangeNotice notice) {
        return MdcScope.open("correlationId", notice.correlationId())
                .and("tenantId", notice.tenantId())
                .and("eventId", Long.toString(notice.eventId()))
                .and("employeeId", notice.aggregateId());
    }
}
