package com.moveinsync.hrsync.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.hrsync.booking.BookingClient;
import com.moveinsync.hrsync.booking.Cancellation;
import com.moveinsync.hrsync.domain.ChangeNotice;
import com.moveinsync.hrsync.domain.OutboxEvents;
import com.moveinsync.hrsync.metrics.SyncMeters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

@Component
public class DeactivationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeactivationEventConsumer.class);

    private final JdbcTemplate jdbc;
    private final BookingClient bookings;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final SyncMeters meters;
    private volatile Runnable afterCancel = () -> {
    };

    public DeactivationEventConsumer(
            JdbcTemplate jdbc,
            BookingClient bookings,
            ObjectMapper objectMapper,
            Clock clock,
            SyncMeters meters
    ) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.meters = meters;
    }

    public void setAfterCancel(Runnable afterCancel) {
        this.afterCancel = afterCancel == null ? () -> {
        } : afterCancel;
    }

    public void handle(ChangeNotice notice) {
        if (notice == null || !OutboxEvents.EMPLOYEE_DEACTIVATED.equals(notice.eventType())) {
            return;
        }
        try {
            apply(notice);
        } catch (RuntimeException exception) {
            meters.deactivationFailed();
            throw exception;
        }
    }

    private void apply(ChangeNotice notice) {
        if (alreadyHandled(notice.eventId(), notice.tenantId())) {
            return;
        }
        Cancellation cancellation = bookings.cancelFutureBookings(
                notice.tenantId(),
                notice.aggregateId(),
                notice.createdAt(),
                notice.eventId()
        );
        afterCancel.run();
        jdbc.update(
                """
                        insert into deactivation_effect (
                            event_id, tenant_id, employee_id, cancelled_booking_ids, seats_freed, processed_at
                        )
                        values (?, ?, ?, cast(? as jsonb), ?, ?)
                        """,
                notice.eventId(),
                notice.tenantId(),
                notice.aggregateId(),
                bookingIds(cancellation),
                cancellation.seatsFreed(),
                Timestamp.from(clock.instant())
        );
        log.info(
                "deactivation effects eventId={} tenantId={} employeeId={} seatsFreed={} cancelledCount={}",
                notice.eventId(),
                notice.tenantId(),
                notice.aggregateId(),
                cancellation.seatsFreed(),
                cancellation.bookingIds().size()
        );
        meters.bookingsCancelled(cancellation.bookingIds().size());
        meters.seatsFreed(cancellation.seatsFreed());
    }

    private boolean alreadyHandled(long eventId, String tenantId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from deactivation_effect where event_id = ? and tenant_id = ?",
                Integer.class,
                eventId,
                tenantId
        );
        return count != null && count > 0;
    }

    private String bookingIds(Cancellation cancellation) {
        try {
            return objectMapper.writeValueAsString(cancellation.bookingIds());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write deactivation effect");
        }
    }
}
