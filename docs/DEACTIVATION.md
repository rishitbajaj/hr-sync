# Deactivation side effects

An HR `DEACTIVATE` and a reconciliation deactivation after approval both write one outbox row with event type `EMPLOYEE_DEACTIVATED`. The payload is the employee id and `changedFields: ["status"]`. It has no name, email, phone, or pickup value. The inbound event type stays `DEACTIVATE`.

The poller claims that row and `DeactivationEventConsumer` runs in the same transaction, before the row is marked `PUBLISHED`.

## What "future" means

A booking is future when its start is after the outbox row's `created_at`. That is the time the deactivation was recorded, and it is the same value on every redelivery. Past bookings have already ended. In-progress bookings have started and have not ended. Both have a start at or before that cutoff, so they are left alone. A start equal to the cutoff is kept.

The cutoff is not the time the consumer happens to run, and it is not the time HR sent the deactivate. A cab that started while the event was waiting is already underway and is not cancelled out from under the rider.

## Idempotency

The booking store is JDBC, in schema `booking_svc`, and each cancel commits in its own transaction. The update sets `status = 'CANCELLED'` and `cancelled_by_event_id` only where `status = 'BOOKED'` and `starts_at` is after the cutoff. The booking ids and seats freed are then read back by `cancelled_by_event_id`. A repeat of the same event updates nothing and returns that same result, so seats are not freed twice. Occupancy is the count of `BOOKED` rows on the trip, not a stored counter.

The consumer also inserts `deactivation_effect` keyed by the outbox event id, in the consumer's transaction. A second delivery of an event whose effect row committed does not call the client again. If that transaction rolls back after the booking transaction has committed, the redelivery calls the client again and reads the same cancellation back.

## Failure

If the booking client throws, the consumer throws. The poller keeps the outbox row `PENDING`, increments attempts, and backs off. Nothing is marked cancelled in `deactivation_effect`, so the retry is the delivery that performs the cancellation. No failure is swallowed.

While that row is still `PENDING`, the claim query's per-employee guard will not deliver a later event for the same employee. That wait is acceptable: the cancellation should stay ahead of a later notice for that person, and a later create does not put the bookings back. Other employees are still claimed. When attempts are exhausted the row becomes `FAILED`. A `FAILED` `EMPLOYEE_DEACTIVATED` row keeps blocking later events for that employee until an operator replays or discards it. Other employees are unaffected. The trade-off is that one poison deactivation holds that one employee, and nobody else, until someone handles it.

An operator lists failed rows with `GET /outbox/failed` (employee id, event id, attempts, and a reason code; no personal values). `POST /outbox/{id}/replay` sets that row back to `PENDING` with attempts reset. `POST /outbox/{id}/discard` requires a reason and writes it to `outbox_discard` in the same transaction that marks the row `DISCARDED`.

## A later create

A create after deactivation makes the employee `ACTIVE` again and emits `CREATE`, not `EMPLOYEE_DEACTIVATED`. The consumer ignores it. Cancelled bookings stay cancelled.

## Reconciliation

Approval calls `deactivateIfUnchanged`, which writes the same `EMPLOYEE_DEACTIVATED` row as an HR deactivate. The poller and consumer do not have a second path.

## Demo

Start with the `demo` profile. These endpoints are not loaded otherwise.

```
POST /demo/trips     {"tenantId":"acme","tripId":"trip-1","capacity":4}
POST /demo/bookings  {"bookingId":"b1","tenantId":"acme","employeeId":"emp-1","tripId":"trip-1","startsAt":"2026-11-02T09:00:00Z"}
GET  /demo/bookings
```

The bodies carry ids and times only.
