package com.moveinsync.hrsync.booking;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class MockBookingService implements BookingClient {

    private final ConcurrentMap<String, Trip> trips = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Booking> bookings = new ConcurrentHashMap<>();
    private int failuresLeft;
    private int timeoutsLeft;
    private int cancelCalls;

    public synchronized TripView seedTrip(String tripId, int capacity) {
        requireId(tripId, "tripId");
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity");
        }
        Trip trip = trips.get(tripId);
        if (trip == null) {
            trip = new Trip(tripId, capacity);
            trips.put(tripId, trip);
        }
        return trip.view();
    }

    public synchronized BookingView seedBooking(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt,
            Instant endsAt
    ) {
        requireId(bookingId, "bookingId");
        requireId(tenantId, "tenantId");
        requireId(employeeId, "employeeId");
        requireId(tripId, "tripId");
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("interval");
        }
        Booking existing = bookings.get(bookingId);
        if (existing != null) {
            return existing.view();
        }
        Trip trip = trips.get(tripId);
        if (trip == null) {
            throw new IllegalArgumentException("trip");
        }
        if (trip.occupied >= trip.capacity) {
            throw new IllegalArgumentException("capacity");
        }
        Booking booking = new Booking(bookingId, tenantId, employeeId, tripId, startsAt, endsAt);
        bookings.put(bookingId, booking);
        trip.occupied++;
        return booking.view();
    }

    public synchronized void failNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times");
        }
        failuresLeft = times;
    }

    public synchronized void timeoutNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times");
        }
        timeoutsLeft = times;
    }

    public synchronized void reset() {
        trips.clear();
        bookings.clear();
        failuresLeft = 0;
        timeoutsLeft = 0;
        cancelCalls = 0;
    }

    public synchronized int cancelCalls() {
        return cancelCalls;
    }

    public synchronized int occupied(String tripId) {
        Trip trip = trips.get(tripId);
        return trip == null ? 0 : trip.occupied;
    }

    public synchronized boolean isCancelled(String bookingId) {
        Booking booking = bookings.get(bookingId);
        return booking != null && booking.cancelled;
    }

    public synchronized Board board() {
        List<TripView> tripViews = trips.values().stream()
                .map(Trip::view)
                .sorted(Comparator.comparing(TripView::tripId))
                .toList();
        List<BookingView> bookingViews = bookings.values().stream()
                .map(Booking::view)
                .sorted(Comparator.comparing(BookingView::bookingId))
                .toList();
        return new Board(tripViews, bookingViews);
    }

    @Override
    public synchronized Cancellation cancelFutureBookings(String tenantId, String employeeId, Instant asOf) {
        cancelCalls++;
        if (timeoutsLeft > 0) {
            timeoutsLeft--;
            throw new BookingClientException("timeout");
        }
        if (failuresLeft > 0) {
            failuresLeft--;
            throw new BookingClientException("unavailable");
        }
        if (tenantId == null || employeeId == null || asOf == null) {
            return Cancellation.none();
        }
        List<Booking> due = new ArrayList<>();
        for (Booking booking : bookings.values()) {
            if (booking.cancelled) {
                continue;
            }
            if (!tenantId.equals(booking.tenantId) || !employeeId.equals(booking.employeeId)) {
                continue;
            }
            if (booking.startsAt.isAfter(asOf)) {
                due.add(booking);
            }
        }
        due.sort(Comparator.comparing(booking -> booking.bookingId));
        List<String> ids = new ArrayList<>();
        for (Booking booking : due) {
            booking.cancelled = true;
            Trip trip = trips.get(booking.tripId);
            if (trip != null && trip.occupied > 0) {
                trip.occupied--;
            }
            ids.add(booking.bookingId);
        }
        return new Cancellation(ids, ids.size());
    }

    private static void requireId(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field);
        }
    }

    public record TripView(String tripId, int capacity, int occupied) {
    }

    public record BookingView(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt,
            Instant endsAt,
            boolean cancelled
    ) {
    }

    public record Board(List<TripView> trips, List<BookingView> bookings) {
    }

    private static final class Trip {
        private final String tripId;
        private final int capacity;
        private int occupied;

        private Trip(String tripId, int capacity) {
            this.tripId = tripId;
            this.capacity = capacity;
        }

        private TripView view() {
            return new TripView(tripId, capacity, occupied);
        }
    }

    private static final class Booking {
        private final String bookingId;
        private final String tenantId;
        private final String employeeId;
        private final String tripId;
        private final Instant startsAt;
        private final Instant endsAt;
        private boolean cancelled;

        private Booking(
                String bookingId,
                String tenantId,
                String employeeId,
                String tripId,
                Instant startsAt,
                Instant endsAt
        ) {
            this.bookingId = bookingId;
            this.tenantId = tenantId;
            this.employeeId = employeeId;
            this.tripId = tripId;
            this.startsAt = startsAt;
            this.endsAt = endsAt;
        }

        private BookingView view() {
            return new BookingView(bookingId, tenantId, employeeId, tripId, startsAt, endsAt, cancelled);
        }
    }
}
