package com.moveinsync.hrsync.booking;

import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Failure injection in front of the JDBC booking store. A lost response is thrown only after
 * the store's own transaction has committed, which is the "service succeeded, caller did not hear" case.
 */
@Service
public class MockBookingService implements BookingClient {

    private final JdbcBookingService store;
    private int failuresLeft;
    private int timeoutsLeft;
    private int lostResponsesLeft;
    private int cancelCalls;

    public MockBookingService(JdbcBookingService store) {
        this.store = store;
    }

    public JdbcBookingService.TripView seedTrip(String tenantId, String tripId, int capacity) {
        return store.seedTrip(tenantId, tripId, capacity);
    }

    public JdbcBookingService.BookingView seedBooking(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt
    ) {
        return store.seedBooking(bookingId, tenantId, employeeId, tripId, startsAt);
    }

    public void failNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times");
        }
        failuresLeft = times;
    }

    public void timeoutNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times");
        }
        timeoutsLeft = times;
    }

    public void loseResponseNext(int times) {
        if (times < 0) {
            throw new IllegalArgumentException("times");
        }
        lostResponsesLeft = times;
    }

    public void reset() {
        failuresLeft = 0;
        timeoutsLeft = 0;
        lostResponsesLeft = 0;
        cancelCalls = 0;
        store.reset();
    }

    public int cancelCalls() {
        return cancelCalls;
    }

    public int occupied(String tripId) {
        return store.occupied(tripId);
    }

    public boolean isCancelled(String bookingId) {
        return store.isCancelled(bookingId);
    }

    public JdbcBookingService.Board board() {
        return store.board();
    }

    @Override
    public Cancellation cancelFutureBookings(String tenantId, String employeeId, Instant cutoff, long eventId) {
        boolean timeout;
        boolean unavailable;
        synchronized (this) {
            cancelCalls++;
            timeout = timeoutsLeft > 0;
            if (timeout) {
                timeoutsLeft--;
            }
            unavailable = !timeout && failuresLeft > 0;
            if (unavailable) {
                failuresLeft--;
            }
        }
        if (timeout) {
            throw new BookingClientException("timeout");
        }
        if (unavailable) {
            throw new BookingClientException("unavailable");
        }
        Cancellation cancellation = store.cancelCommitted(tenantId, employeeId, cutoff, eventId);
        boolean lost;
        synchronized (this) {
            lost = lostResponsesLeft > 0;
            if (lost) {
                lostResponsesLeft--;
            }
        }
        if (lost) {
            throw new BookingClientException("response lost");
        }
        return cancellation;
    }
}
