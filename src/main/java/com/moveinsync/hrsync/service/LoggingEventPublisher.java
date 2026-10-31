package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.EventPublisher;
import com.moveinsync.hrsync.observability.MdcScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final List<ChangeNotice> published = new CopyOnWriteArrayList<>();
    private volatile Consumer<ChangeNotice> listener = notice -> {
    };

    @Override
    public void publish(ChangeNotice notice) {
        try (MdcScope ignored = MdcScope.open("correlationId", notice.correlationId())
                .and("tenantId", notice.tenantId())
                .and("eventId", Long.toString(notice.eventId()))
                .and("employeeId", notice.aggregateId())) {
            log.info(
                    "published eventId={} tenantId={} aggregateId={} eventType={} changedFields={}",
                    notice.eventId(),
                    notice.tenantId(),
                    notice.aggregateId(),
                    notice.eventType(),
                    notice.changedFields()
            );
            published.add(notice);
            listener.accept(notice);
        }
    }

    public List<ChangeNotice> published() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }

    public void setListener(Consumer<ChangeNotice> listener) {
        this.listener = listener == null ? notice -> {
        } : listener;
    }
}
