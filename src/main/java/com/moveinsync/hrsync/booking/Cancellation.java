package com.moveinsync.hrsync.booking;

import java.util.List;

public record Cancellation(List<String> bookingIds, int seatsFreed) {

    public Cancellation {
        bookingIds = List.copyOf(bookingIds);
    }

    public static Cancellation none() {
        return new Cancellation(List.of(), 0);
    }
}
