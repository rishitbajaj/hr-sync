# Conflict rules

HR events and transport-admin events are applied per employee, per channel. A no-op does not change the employee row, does not insert `processed_event`, and does not write an outbox row. There is no `ALREADY_EXISTS` outcome. A no-op is `IGNORED` with reason `DUPLICATE` or `STALE_IGNORED`.

## Idempotency key

The idempotency key is `(tenant_id, employee_id, channel, source_version)`, stored as a unique row in `processed_event`.

`channel` is part of the key because HR and transport-admin keep separate version sequences. HR version 1 and transport version 1 are different events. A key of only `(tenant, employee, source_version)` would treat them as one event and drop the second.

A matching `processed_event` row is checked before the version comparison. The outcome is `DUPLICATE`. The employee row is left as it is.

## Version comparison

HR events compare `source_version` with `employee.hr_version`. Transport-admin events compare `source_version` with `employee.transport_version`. One channel's version never makes the other channel stale.

When the incoming version is less than or equal to the stored version for that channel, the outcome is `STALE_IGNORED`.

A version that was applied is both stored on the employee and recorded in `processed_event`, so a later replay of that same event is `DUPLICATE`. A version that was never applied, and is not newer than the stored version, is `STALE_IGNORED`.

The employee change and the `processed_event` insert commit in the same transaction. The write is conditional:

```sql
update employee
set ...
where tenant_id = :tenant and employee_id = :employee and hr_version < :version
```

Transport updates use the same shape with `transport_version`. If a concurrent writer already stored an equal or higher version, the update matches zero rows and this attempt is `STALE_IGNORED`. Two writers inserting the same new employee are serialized by the employee unique key; the loser retries in a new transaction and then follows the rules above.

## Field ownership

HR events may change only `name`, `email`, `phone`, `manager`, `office_code`, `hr_version`, and `status`. Transport-admin events (`POST /sync/transport-events`) may change only `pickup_point`, `special_assistance`, and `transport_version`.

An HR payload that also contains a pickup point or special-assistance value does not write those columns. A transport payload that contains name, email, phone, manager, or office does not write those columns and does not change status.

## Tombstone rule

An update or deactivate for an employee that has no row yet still records the version. The service inserts a placeholder row so a later create with a lower version is `STALE_IGNORED` instead of being applied late.

- An HR update inserts the HR fields from that event, status `ACTIVE`, `hr_version` set to the event version, and `transport_version` 0. Transport columns stay null.
- An HR deactivate inserts status `DEACTIVATED`, `hr_version` set to the event version, and null HR and transport fields. Deactivate does not copy HR fields onto the placeholder.
- A transport-admin change inserts the transport fields, `transport_version` set to the event version, `hr_version` 0, and status `ACTIVE`.

A later create whose version is less than or equal to the stored `hr_version` is `STALE_IGNORED`. A later create with a higher version applies the HR fields and sets status `ACTIVE`, including when the placeholder was deactivated. There is no separate placeholder status: `employee.status` stays `ACTIVE` or `DEACTIVATED`.

## Ordering inside one batch

Before applying, the batch is sorted by tenant, employee, then `source_version`. The response stays in the request's original order. Invalid records are rejected in place and are not part of the sorted apply sequence.

## Examples

Office transfer version 7 arrives, then an earlier HR change version 6. There is no shift column; the version 7 name is the rest of that HR state. Version 7 is `APPLIED`. Version 6 is `STALE_IGNORED`. The employee remains in the version 7 office with the version 7 name and `hr_version` 7.

The same feed is posted again. Every second-pass result is `DUPLICATE` or `STALE_IGNORED`. Names, contact fields, office, pickup point, assistance, both versions, status, and `updated_at` stay as they were.

Update version 5 arrives for employee `emp-tomb-u` before any create. The row stores that version. Create version 3 is `STALE_IGNORED` and the version 5 fields remain.

Deactivate version 4 arrives for employee `emp-tomb-d` before any create. The row is `DEACTIVATED` at `hr_version` 4. Create version 2 is `STALE_IGNORED` and the employee stays deactivated. Create version 6 is newer, so it is `APPLIED`, the HR fields are stored, and the status becomes `ACTIVE`.

Employee `emp-own` has pickup point `Gate 4` and special assistance `wheelchair` at transport version 1. An HR update version 2 changes the name and also sends a pickup point and special-assistance value. The name and `hr_version` change. The pickup point, special assistance, and `transport_version` do not.

One batch for employee `emp-batch` contains versions 1, 5, 3, 4, 2 in that request order. They are applied in version order. The stored row is the version 5 state, and the response lists the results in the original request order.
