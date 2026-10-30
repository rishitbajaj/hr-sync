package com.moveinsync.hrsync.booking;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * JDBC stand-in for an external booking service. Each call commits in its own transaction,
 * so a rollback of the HR outbox transaction does not undo a cancellation.
 */
@Service
public class JdbcBookingService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public JdbcBookingService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public TripView seedTrip(String tenantId, String tripId, int capacity) {
        requireId(tenantId, "tenantId");
        requireId(tripId, "tripId");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity");
        }
        return transactions.execute(status -> {
            jdbc.update(
                    """
                            insert into booking_svc.trip (tenant_id, trip_id, capacity)
                            values (?, ?, ?)
                            on conflict (tenant_id, trip_id) do nothing
                            """,
                    tenantId,
                    tripId,
                    capacity
            );
            return loadTrip(tenantId, tripId);
        });
    }

    public BookingView seedBooking(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt
    ) {
        requireId(bookingId, "bookingId");
        requireId(tenantId, "tenantId");
        requireId(employeeId, "employeeId");
        requireId(tripId, "tripId");
        if (startsAt == null) {
            throw new IllegalArgumentException("startsAt");
        }
        return transactions.execute(status -> {
            BookingView existing = findBooking(tenantId, bookingId);
            if (existing != null) {
                return existing;
            }
            TripView trip = loadTrip(tenantId, tripId);
            if (trip == null) {
                throw new IllegalArgumentException("trip");
            }
            if (trip.occupied() >= trip.capacity()) {
                throw new IllegalArgumentException("capacity");
            }
            jdbc.update(
                    """
                            insert into booking_svc.booking (
                                tenant_id, booking_id, employee_id, trip_id, starts_at, status
                            )
                            values (?, ?, ?, ?, ?, 'BOOKED')
                            """,
                    tenantId,
                    bookingId,
                    employeeId,
                    tripId,
                    Timestamp.from(startsAt)
            );
            return findBooking(tenantId, bookingId);
        });
    }

    public Cancellation cancelCommitted(String tenantId, String employeeId, Instant cutoff, long eventId) {
        if (tenantId == null || employeeId == null || cutoff == null) {
            return Cancellation.none();
        }
        return transactions.execute(status -> {
            jdbc.update(
                    """
                            update booking_svc.booking
                            set status = 'CANCELLED',
                                cancelled_by_event_id = ?,
                                cancelled_at = ?
                            where tenant_id = ?
                              and employee_id = ?
                              and starts_at > ?
                              and status = 'BOOKED'
                            """,
                    eventId,
                    Timestamp.from(clock.instant()),
                    tenantId,
                    employeeId,
                    Timestamp.from(cutoff)
            );
            return readCancellation(tenantId, eventId);
        });
    }

    public void reset() {
        transactions.executeWithoutResult(status -> {
            jdbc.update("delete from booking_svc.booking");
            jdbc.update("delete from booking_svc.trip");
        });
    }

    public int occupied(String tripId) {
        Integer count = jdbc.queryForObject(
                """
                        select count(*) from booking_svc.booking
                        where trip_id = ? and status = 'BOOKED'
                        """,
                Integer.class,
                tripId
        );
        return count == null ? 0 : count;
    }

    public boolean isCancelled(String bookingId) {
        Integer count = jdbc.queryForObject(
                """
                        select count(*) from booking_svc.booking
                        where booking_id = ? and status = 'CANCELLED'
                        """,
                Integer.class,
                bookingId
        );
        return count != null && count > 0;
    }

    public Board board() {
        List<TripView> trips = jdbc.query(
                """
                        select t.tenant_id, t.trip_id, t.capacity,
                               (select count(*) from booking_svc.booking b
                                 where b.tenant_id = t.tenant_id and b.trip_id = t.trip_id and b.status = 'BOOKED') as occupied
                        from booking_svc.trip t
                        order by t.tenant_id, t.trip_id
                        """,
                (rs, row) -> new TripView(
                        rs.getString("tenant_id"),
                        rs.getString("trip_id"),
                        rs.getInt("capacity"),
                        rs.getInt("occupied")
                )
        );
        List<BookingView> bookings = jdbc.query(
                """
                        select booking_id, tenant_id, employee_id, trip_id, starts_at, status
                        from booking_svc.booking
                        order by booking_id
                        """,
                (rs, row) -> mapBooking(rs)
        );
        return new Board(trips, bookings);
    }

    private Cancellation readCancellation(String tenantId, long eventId) {
        List<String> ids = jdbc.query(
                """
                        select booking_id from booking_svc.booking
                        where tenant_id = ? and cancelled_by_event_id = ?
                        order by booking_id
                        """,
                (rs, row) -> rs.getString(1),
                tenantId,
                eventId
        );
        return new Cancellation(ids, ids.size());
    }

    private TripView loadTrip(String tenantId, String tripId) {
        List<TripView> trips = jdbc.query(
                """
                        select t.tenant_id, t.trip_id, t.capacity,
                               (select count(*) from booking_svc.booking b
                                 where b.tenant_id = t.tenant_id and b.trip_id = t.trip_id and b.status = 'BOOKED') as occupied
                        from booking_svc.trip t
                        where t.tenant_id = ? and t.trip_id = ?
                        """,
                (rs, row) -> new TripView(
                        rs.getString("tenant_id"),
                        rs.getString("trip_id"),
                        rs.getInt("capacity"),
                        rs.getInt("occupied")
                ),
                tenantId,
                tripId
        );
        return trips.isEmpty() ? null : trips.get(0);
    }

    private BookingView findBooking(String tenantId, String bookingId) {
        List<BookingView> rows = jdbc.query(
                """
                        select booking_id, tenant_id, employee_id, trip_id, starts_at, status
                        from booking_svc.booking
                        where tenant_id = ? and booking_id = ?
                        """,
                (rs, row) -> mapBooking(rs),
                tenantId,
                bookingId
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static BookingView mapBooking(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp startsAt = rs.getTimestamp("starts_at");
        return new BookingView(
                rs.getString("booking_id"),
                rs.getString("tenant_id"),
                rs.getString("employee_id"),
                rs.getString("trip_id"),
                startsAt.toInstant(),
                "CANCELLED".equals(rs.getString("status"))
        );
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
    }

    public record TripView(String tenantId, String tripId, int capacity, int occupied) {
    }

    public record BookingView(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt,
            boolean cancelled
    ) {
    }

    public record Board(List<TripView> trips, List<BookingView> bookings) {
    }
}
