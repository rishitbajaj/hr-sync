# Deactivation side effects

An HR `DEACTIVATE` and a reconciliation deactivation after approval both write one outbox row with event type `EMPLOYEE_DEACTIVATED`. The payload is the employee id and `changedFields: ["status"]`. It has no name, email, phone, or pickup value. The inbound event type stays `DEACTIVATE`.

The poller claims that row and `DeactivationEventConsumer` runs in the same transaction, before the row is marked `PUBLISHED`.

## What "future" means

A booking is future when its start is after the time the consumer processes the event. Past bookings have already ended. In-progress bookings have started and have not ended. Both have a start at or before that processing time, so they are left alone.

The clock is the processing time, not the time HR sent the deactivate. A cab that started while the event was waiting is already underway and is not cancelled out from under the rider.

## Idempotency

`BookingClient.cancelFutureBookings` is a no-op for a booking that is already cancelled or absent. Seats are freed only for a booking that changes from booked to cancelled. The mock keeps a seat count per trip.

The consumer also inserts `deactivation_effect` keyed by the outbox event id, in the same transaction as that record of the effect. A second delivery of an event whose effect row committed does not call the client again. If the process dies after the in-memory cancel but before the transaction commits, the effect row rolls back and the redelivery calls the client again. The client then frees no further seats.

## Failure

If the booking client throws, the consumer throws. The poller keeps the outbox row `PENDING`, increments attempts, and backs off. Nothing is marked cancelled in `deactivation_effect`, so the retry is the delivery that performs the cancellation. No failure is swallowed.

While that row is still `PENDING`, the claim query's per-employee guard will not deliver a later event for the same employee. That wait is acceptable: the cancellation should stay ahead of a later notice for that person, and a later create does not put the bookings back. Other employees are still claimed. When attempts are exhausted the row becomes `FAILED` and stays for an operator. The guard only looks at `PENDING` rows.

## A later create

A create after deactivation makes the employee `ACTIVE` again and emits `CREATE`, not `EMPLOYEE_DEACTIVATED`. The consumer ignores it. Cancelled bookings stay cancelled.

## Reconciliation

Approval calls `deactivateIfUnchanged`, which writes the same `EMPLOYEE_DEACTIVATED` row as an HR deactivate. The poller and consumer do not have a second path.

## Demo

Start with the `demo` profile. These endpoints are not loaded otherwise.

```
POST /demo/trips     {"tripId":"trip-1","capacity":4}
POST /demo/bookings  {"bookingId":"b1","tenantId":"acme","employeeId":"emp-1","tripId":"trip-1","startsAt":"2026-11-02T09:00:00Z","endsAt":"2026-11-02T10:00:00Z"}
GET  /demo/bookings
```

The bodies carry ids and times only.
