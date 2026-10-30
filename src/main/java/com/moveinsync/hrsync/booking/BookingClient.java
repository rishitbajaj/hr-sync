package com.moveinsync.hrsync.booking;

import java.time.Instant;

public interface BookingClient {

    Cancellation cancelFutureBookings(String tenantId, String employeeId, Instant cutoff, long eventId);
}
