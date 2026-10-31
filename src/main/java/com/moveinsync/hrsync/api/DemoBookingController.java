package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.booking.JdbcBookingService;
import com.moveinsync.hrsync.booking.MockBookingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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

    @Operation(summary = "Seed a demo trip", description = "Open. Demo profile only.")
    @ApiResponse(responseCode = "200", description = "The trip.")
    @ApiResponse(responseCode = "400", description = "Invalid seed.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/demo/trips")
    public JdbcBookingService.TripView seedTrip(@RequestBody TripSeed request) {
        return bookings.seedTrip(request.tenantId(), request.tripId(), request.capacity());
    }

    @Operation(summary = "Seed a demo booking", description = "Open. Demo profile only. The body has no name, email, or phone.")
    @ApiResponse(responseCode = "200", description = "The booking.")
    @ApiResponse(responseCode = "400", description = "Invalid seed.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/demo/bookings")
    public JdbcBookingService.BookingView seedBooking(@RequestBody BookingSeed request) {
        return bookings.seedBooking(
                request.bookingId(),
                request.tenantId(),
                request.employeeId(),
                request.tripId(),
                request.startsAt()
        );
    }

    @Operation(summary = "Read the demo board", description = "Open. Demo profile only.")
    @ApiResponse(responseCode = "200", description = "Trips and bookings.")
    @GetMapping("/demo/bookings")
    public JdbcBookingService.Board inspect() {
        return bookings.board();
    }

    @Operation(summary = "Arm demo booking failures", description = "Open. Demo profile only. The next cancel calls fail, time out, or lose the response the given number of times.")
    @ApiResponse(responseCode = "200", description = "The armed counts.")
    @PostMapping("/demo/failures")
    public FailureInjection failures(@RequestBody FailureInjection request) {
        int failures = request == null || request.failures() == null ? 0 : request.failures();
        int timeouts = request == null || request.timeouts() == null ? 0 : request.timeouts();
        int lostResponses = request == null || request.lostResponses() == null ? 0 : request.lostResponses();
        bookings.failNext(failures);
        bookings.timeoutNext(timeouts);
        bookings.loseResponseNext(lostResponses);
        return new FailureInjection(failures, timeouts, lostResponses);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_BOOKING", "Invalid booking seed"));
    }

    public record FailureInjection(Integer failures, Integer timeouts, Integer lostResponses) {
    }

    public record TripSeed(
            @Schema(example = "tenant-a") String tenantId,
            @Schema(example = "trip-1") String tripId,
            @Schema(example = "4") int capacity
    ) {
    }

    public record BookingSeed(
            @Schema(example = "booking-1") String bookingId,
            @Schema(example = "tenant-a") String tenantId,
            @Schema(example = "employee-1") String employeeId,
            @Schema(example = "trip-1") String tripId,
            @Schema(example = "2026-12-01T09:00:00Z") Instant startsAt
    ) {
    }
}
