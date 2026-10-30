package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.booking.MockBookingService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@Profile("demo")
@RestController
public class DemoBookingController {

    private final MockBookingService bookings;

    public DemoBookingController(MockBookingService bookings) {
        this.bookings = bookings;
    }

    @PostMapping("/demo/trips")
    public MockBookingService.TripView seedTrip(@RequestBody TripSeed request) {
        return bookings.seedTrip(request.tripId(), request.capacity());
    }

    @PostMapping("/demo/bookings")
    public MockBookingService.BookingView seedBooking(@RequestBody BookingSeed request) {
        return bookings.seedBooking(
                request.bookingId(),
                request.tenantId(),
                request.employeeId(),
                request.tripId(),
                request.startsAt(),
                request.endsAt()
        );
    }

    @GetMapping("/demo/bookings")
    public MockBookingService.Board inspect() {
        return bookings.board();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_BOOKING", "Invalid booking seed"));
    }

    public record TripSeed(String tripId, int capacity) {
    }

    public record BookingSeed(
            String bookingId,
            String tenantId,
            String employeeId,
            String tripId,
            Instant startsAt,
            Instant endsAt
    ) {
    }
}
