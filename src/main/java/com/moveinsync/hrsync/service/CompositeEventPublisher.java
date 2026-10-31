package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;

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
}
