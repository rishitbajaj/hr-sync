# Audit: Phase 6 deactivation side effects

Read-only audit of `phase-6-deactivation` at `83e36c3`. Phase 7 was not started. Nothing was pushed. Temporary probes and mutations were reverted. The only file this audit adds is this report.

A fresh clone was made at `/tmp/hr-sync-phase6-audit` (same commit). Host ports 5432 and 8080 were already taken by the existing `hr-sync-audit` stack, which was left running. The clone's compose file was remapped to host ports 5434 and 8081 for this run. The branch's `docker-compose.yml` is unchanged and still publishes 5432 and 8080, with no Spring profile.

Machine: WSL2 Linux `romrom` 6.6.87.2-microsoft-standard-WSL2, AMD Ryzen 7 7840HS (8 cores / 16 threads), MemTotal 7,760,064 kB, Docker 29.4.2, Compose v5.1.3, Temurin 21.0.13+9, PostgreSQL 16.15. Measured 2026-10-30.

Verdicts are PASS, PARTIAL, or FAIL. A PASS cites a test, a file, or command output.

## Verdicts

| Item | Verdict | Evidence |
| --- | --- | --- |
| Fresh clone `mvn clean test` | PASS | Clone at `83e36c3`. `mvn -B clean test` BUILD SUCCESS. Default surefire: Tests run: 68, Failures: 0, Errors: 0, Skipped: 0. Benchmark: Tests run: 1, Failures: 0. Wall clock 01:20 min. Benchmark line: `reconciliation benchmark rows=200000 elapsedMs=1848 peakHeapBytes=86010056 maxHeapBytes=268435456` |
| Earlier ordering, outbox order, reconciliation, brake | PASS | Same run: `OrderingScenariosTest` 9 tests, `OutboxPollerTest` 11 (includes `publishOrderFollowsPerEmployeeVersionOrder` and `twoPollersPublishEachRowOnceAndSkipNone`), `ReconciliationRunTest` 12 (includes `cutoffExportTripsTheSafetyBrake`), `ReconciliationBenchmarkTest` 1. All 0 failures |
| `docker compose up --build` on an empty database | PASS | Clone stack `phase6audit`. App log: schema `<< Empty Schema >>`, migrations 1 through 13, `Successfully applied 13 migrations to schema "public", now at version v13`, `Started HrSyncApplication` in 6.859 s. `GET /health` on port 8081 returned 200 `{"status":"UP"}` |
| Flyway on a database already at V12, then V13 | PASS | Jar built from the clone with `V13` removed: `Successfully applied 12 migrations ... now at version v12`. `\d deactivation_effect` was missing. History had versions 1–12, `success = t`. The process then exited because port 8080 was in use; Flyway had already committed. Jar rebuilt with `V13` restored, `SERVER_PORT=8082`: `Migrating schema "public" to version "13 - deactivation effect"`, `Successfully applied 1 migration ... now at version v13`. History 1–13 all `success = t`. Table has primary key `deactivation_effect_pkey` on `event_id` |
| HR deactivate emits `EMPLOYEE_DEACTIVATED` | PASS | `EmployeeChangeService.insertOutbox` calls `insertDeactivateNotice` for `EventType.DEACTIVATE`. `DeactivationSideEffectTest.deactivationCancelsFutureBookingsOnly` applies a `DEACTIVATE` and `poll()` returns 1. Live payload below |
| Approved reconciliation emits the same type | PASS | `ReconciliationService.approve` calls `deactivateIfUnchanged`, which calls `insertDeactivateNotice`. `DeactivationSideEffectTest.anApprovedReconciliationDeactivationCancelsFutureBookings`. `ReconciliationRunTest` reads `event_type = 'EMPLOYEE_DEACTIVATED'` for `emp-miss` |
| Stale, duplicate, and rejected deactivations emit nothing | PASS | `DeactivationSideEffectTest.duplicateAndStaleDeactivationCancelOnce`: v3 `APPLIED`, same v3 `DUPLICATE`, v2 `STALE_IGNORED`, `poll()` is 1 then 0, `cancelCalls` is 1. Probe `tombstoneRejectedStaleAndPayload`: invalid phone `REJECTED` / `INVALID_PHONE`, then one applied deactivate, duplicate, and stale; outbox count stayed 2 (the create plus that one deactivation). Reconciliation version race: `ReconciliationRunTest.liveHrEventBetweenDiffAndApplyIsNotDeactivated` expects 0 `EMPLOYEE_DEACTIVATED` rows for `emp-live` |
| Future cancelled; past and in-progress kept | PASS | `deactivationCancelsFutureBookingsOnly`: `later` cancelled, `past` and `riding` not, other employee's booking not. Predicate is `startsAt.isAfter(asOf)` in `MockBookingService` |
| Boundary: one second before, exactly at processing time, one second after | PASS | Probe called `cancelFutureBookings` with `asOf = 2026-11-01T12:00:00Z`. `before` (asOf minus 1 s) stayed booked, `exact` stayed booked, `after` (asOf plus 1 s) cancelled, seats freed 1, occupied left at 3. A second call returned `seatsFreed=0`. Clock used by the consumer is `clock.instant()` inside `DeactivationEventConsumer.handle`, the processing time. The bean is `Clock.systemUTC()` from `SyncConfig.clock()` and is constructor-injected, so a test can replace the bean. `DeactivationSideEffectTest` does not replace it; it reads `clock.instant()` and offsets bookings by hours or days, so it never hits the exact instant |
| One seat per cancelled booking, including a shared trip | PASS | `seatsFreedEqualsCancelledBookings`: two future bookings, occupied goes to 0, `seats_freed` sum is 2, `cancelCalls` is 1. Probe on one trip of capacity 5: two future for emp-1, one future for emp-2, one past for emp-3. After emp-1's deactivation, occupied was 2 and the other two bookings stayed booked. One client call |
| Zero bookings | PASS | Probe `t-zero`: deactivate and poll succeeded. `deactivation_effect.seats_freed` was 0 and `cancelled_booking_ids` was `[]`. A harmless effect row is written. No exception |
| A later create does not restore bookings | PASS | `DeactivationSideEffectTest.aLaterCreateDoesNotRestoreCancelledBookings`. Consumer returns immediately unless `eventType` is `EMPLOYEE_DEACTIVATED` |
| Deactivate, create, deactivate: second pass cancels only newer bookings | PASS | Probe `t-again`: first deactivation cancelled `old`. After a create, a new booking `fresh` was seeded. Second deactivation cancelled `fresh`. `old` stayed cancelled. Two `deactivation_effect` rows. Occupied 0 |
| Deactivate-before-create | PARTIAL | `docs/DEACTIVATION.md` never mentions it. The code treats it as an applied HR deactivate: status `DEACTIVATED`, not `PLACEHOLDER`, so `insertOutbox` runs. Probe: outcome `APPLIED`, one `EMPLOYEE_DEACTIVATED` row, payload `{"employeeId": "emp-tomb", "changedFields": ["status"]}`, and a future booking was cancelled. An update-before-create stays `PLACEHOLDER` with outbox count 0 (`OutboxPollerTest.placeholderCreationEmitsNoEventUntilActivation`, confirmed again by the probe). That matches `docs/CONFLICT_RULES.md`, which the deactivation doc does not restate |
| `docs/DEACTIVATION.md` matches the code | PARTIAL | The event type, payload, processing-time rule, dedupe, client-throw retry, per-employee `PENDING` guard, later create, and approval path match the code. Gaps are listed under "Doc claims" |
| Crash after a committed effect row | PASS | `aCrashAfterCancellationDoesNotCancelAgainOnRedelivery`: listener throws after publish, effect row is already visible, outbox stays `PENDING`, second poll `cancelCalls` stays 1. Same transaction as `recordFailure` because the listener throw is caught inside `OutboxPoller.deliver` |
| Cancel succeeds, then the effect insert fails | PARTIAL | No committed test forces this window. Probe installed a `BEFORE INSERT` trigger that raised `forced effect failure`. `poll()` threw `UncategorizedSQLException` SQL state `25P02` (`current transaction is aborted`). Booking was already cancelled, occupied 0, `cancelCalls` 1, outbox `PENDING` attempts 0, no effect row. After the trigger was dropped, redelivery called the client again (`cancelCalls` 2), freed no more seats (occupied stayed 0), and wrote `seats_freed = 0`. The seat was not freed twice. The recorded effect under-counts. Attempts were not incremented, because `recordFailure` runs in the aborted transaction and rolls back with it |
| Two pollers, one deactivation | PASS | `OutboxPollerTest.twoPollersPublishEachRowOnceAndSkipNone` covers `FOR UPDATE SKIP LOCKED` on the claim. Probe held the first poller inside the publish listener: the overlapping `poll()` returned 0, the holder returned 1, `cancelCalls` increased by 1, occupied 0 |
| Many redeliveries of one event id | PASS | Probe threw from the listener twice, then let the third delivery finish. `cancelCalls` 1, one effect row, status `PUBLISHED` attempts 2. The committed crash test covers a single redelivery |
| Timeout after the remote call actually succeeded | FAIL | `timeoutNext` throws `BookingClientException("timeout")` before any booking is changed (`MockBookingService` lines 117–123). Probe: one timeout left the booking booked and occupied 1; the retry cancelled it once (`cancelCalls` 2, occupied 0). That is a failure before the cancel, not a timeout after a successful remote cancel. Nothing in the mock can mark the booking cancelled and then throw |
| Client fails twice, then succeeds | PASS | `bookingFailuresAreRetriedWithoutASecondCancellation`: `failNext(2)`, two polls return 0 and the booking stays, third poll publishes, `cancelCalls` 3, one effect row, `seats_freed` 1. Test `retry-base` is `0ms`, so the row is due immediately |
| Client always fails | PASS | `aBookingClientThatKeepsFailingLeavesOneFailedRowAndDoesNotBlockOthers`: three polls, status `FAILED`, event type `EMPLOYEE_DEACTIVATED`, no effect row, booking not cancelled. Hiring `emp-ok` still publishes. There is no outbox HTTP endpoint and no outbox metric. An operator sees the row with `select status, event_type, attempts from outbox where status = 'FAILED'` and the warn `outbox publish exhausted eventId={} tenantId={} aggregateId={} attempts={} error={}`. Actuator exposes `health` and `metrics` only (`application.yml`). `GET /actuator/metrics` on the running app listed JVM, HTTP, and pool meters, no outbox meter. `RetentionJob` deletes `PUBLISHED` rows only, so `FAILED` stays |
| Later events wait while the row is `PENDING` | PASS | Claim SQL `NOT EXISTS` earlier `PENDING` for the same tenant and aggregate (`OutboxPoller`). Probe: deactivation `PENDING` attempts 1, a later `UPDATE` stayed `PENDING` attempts 0, booking not cancelled. After the client recovered, the deactivation published first and the update published on the next poll |
| After the row is `FAILED`, later events proceed | PARTIAL | They do proceed. Probe `t-lost`: deactivation `FAILED` attempts 3, a later `CREATE` published, the future booking stayed booked. `docs/DEACTIVATION.md` says the guard looks only at `PENDING` rows. It does not say that a re-create can pass a failed cancellation and leave the bookings in place. That outcome is unsafe relative to "cancel their future cab bookings": the person is active again and the cab was never cancelled. There is no replay. No controller, poller method, or SQL updates a `FAILED` row back to `PENDING` |
| Backoff and max attempts | PASS | `src/main/resources/application.yml`: poll interval `1s`, batch size `100`, `max-attempts: 5`, `retry-base: 500ms`, `retry-max-delay: 30s`. `retryDelay` doubles the base per attempt, caps it, then picks a random value in `0..delay`. `src/test/resources/application.yml` replaces those with batch size `20`, `max-attempts: 3`, `retry-base: 0ms`, `retry-max-delay: 0ms`, and scheduling off. `retryDelay` returns `Duration.ZERO` when the base is `<= 0`, which is why the failure tests can poll again immediately |
| 1,000 future bookings | PASS | One `cancelFutureBookings` call walks every in-memory booking. Probe: 1,000 bookings, one call, `seatsFreed` 1,000, occupied 0, elapsed 0 ms (below the millisecond timer) |
| 500 employees deactivated together | PASS | Live app, batch 100, poll every 1 s. 500 creates drained with pending counts 300, 200, 100, 0. CSV of one unknown employee: HTTP 200 in 0.109 s, status `HALTED`, `missingCount` 500, `missingRatio` 1.0, `threshold` 0.3, `haltReason` `MISSING_RATIO_EXCEEDED`, `toDeactivate` 0. Approve HTTP 200 in 1.461 s. `employee` rows `DEACTIVATED`: 500. Outbox `EMPLOYEE_DEACTIVATED` samples about 1 s apart: pending 343 / published 157, 243 / 257, 143 / 357, then published 500. Published ids were in order. Wall clock from the start of the diff request to all 500 published: 6.263 s. The approve body still reports `toDeactivate` 0; that field is the halt snapshot. The rows and the outbox events were written |
| EXPLAIN of the booking lookup at 100,000 bookings | FAIL | There is no booking table and no booking SQL. `MockBookingService.cancelFutureBookings` scans `bookings.values()`. Probe: 100,000 future bookings, one call, 46 ms, occupied 0. `EXPLAIN select count(*) from deactivation_effect where event_id = 1` is an Index Only Scan on `deactivation_effect_pkey` (cost 0.27..8.29). That is the dedupe lookup, not a booking lookup. Nothing indexes "future bookings for this employee" |
| `/demo/*` only on the `demo` profile | PASS | `DemoBookingController` is `@Profile("demo")`. Branch `docker-compose.yml` does not set `SPRING_PROFILES_ACTIVE`. Default-profile container: `POST /demo/trips`, `POST /demo/bookings`, and `GET /demo/bookings` each returned HTTP 404 `{"status":404,"error":"Not Found","path":"..."}`. After `SPRING_PROFILES_ACTIVE=demo` was added on the clone only, the log said `The following 1 profile is active: "demo"` and the same routes returned 200 |
| Tenant isolation | PARTIAL | `MockBookingService` skips a booking unless `tenantId` and `employeeId` both match. `deactivation_effect` stores `tenant_id`. Probe: tenant `t-a` deactivation cancelled booking `a` and left tenant `t-b`'s booking `b` for the same employee id; occupied stayed 1. The committed suite has no cross-tenant booking. The mutation that ignored tenant left `DeactivationSideEffectTest` green (10 tests, 0 failures) |
| Logs and payloads omit personal values | PASS | Phase 6 messages are listed below. `deactivationLogsAndPayloadsOmitPersonalValues` checks the outbox payload, `cancelled_booking_ids`, and every root-logger event for name, email, phone, and pickup. Adding `log.info("name={}", "Nola Shore")` failed that test at line 326 on `"name=Nola Shore"`. Live payload: `{"employeeId": "emp-1", "changedFields": ["status"]}`. Scale payload: `{"employeeId": "e0000", "changedFields": ["status"]}`. Compose logs for the scale run had no match for `Person0`, `@example.com`, `+1555`, or `Secret Home` |
| Skip dedupe and drop the effect primary key | PASS | Caught. `aCrashAfterCancellationDoesNotCancelAgainOnRedelivery` line 246: `cancelCalls` expected 1, was 2. The second delivery logged `seatsFreed=0` (the client was still idempotent). No primary-key error, so the assertion is what failed |
| Skip the cancel call | PASS | Caught. `deactivationCancelsFutureBookingsOnly` line 118: expected cancelled, was false |
| Cancel past bookings | PASS | Caught. Same test, line 118: expected the future booking cancelled, was false. Predicate was changed to `!endsAt.isAfter(asOf)` |
| Cancel regardless of tenant | FAIL | Not caught. `DeactivationSideEffectTest` 10 tests, 0 failures, BUILD SUCCESS |
| Free two seats per booking | PASS | Caught. `deactivationCancelsFutureBookingsOnly` line 122: occupied expected 2, was 1. `seatsFreedEqualsCancelledBookings` line 141: expected 2, was 4 |
| Stale deactivation emits an event | PASS | Caught. `duplicateAndStaleDeactivationCancelOnce` line 157: second `poll()` expected 0, was 1 |
| Remove the effect insert | PASS | Caught. Crash test line 243: effect rows expected 1, was 0. Seat test line 141: expected 2, was 0 |
| Booking client not idempotent | FAIL | Not caught. Removing the `booking.cancelled` skip left `DeactivationSideEffectTest` at 10 tests, 0 failures, BUILD SUCCESS. No committed test calls `cancelFutureBookings` twice after a successful cancel |
| Personal value logged | PASS | Caught, as cited above |
| Live demo: seed, deactivate, inspect | PASS | Commands and bodies are in the demo section. Two future bookings cancelled, the past booking kept, occupied 3 then 1, effect `["future-a", "future-b"]` and `seats_freed` 2 |
| Live demo: failure injection, then recovery | FAIL | `failNext` and `timeoutNext` are methods on `MockBookingService`. `DemoBookingController` does not expose them. `POST /demo/failures` returned 404. Recovery is covered by `bookingFailuresAreRetriedWithoutASecondCancellation`, not by the HTTP demo |
| `docker kill` between cancel and publish | PARTIAL | Arranged by a 20 s sleep in the clone jar only (not on the branch). Before the kill, the board already showed the two future bookings cancelled and the past one kept, while the outbox row was `PENDING` attempts 0 and `deactivation_effect` had 0 rows. `docker kill phase6-kill` left that row `PENDING` attempts 0. Restarting the unmodified app published it with `seats_freed` 0 and `cancelled_booking_ids` `[]`, and `GET /demo/bookings` was empty. The in-memory cancel died with the process. Redelivery did not free a second seat because the new process had no bookings |

## What "future" uses for a clock

`DeactivationEventConsumer.handle` passes `clock.instant()` at the moment the poller delivers the event. That is not the time HR sent the deactivate, and the payload has no timestamp. `SyncConfig` defines the bean as `Clock.systemUTC()`. The consumer takes `Clock` in its constructor, so tests can inject a fixed clock. The side-effect tests use the system clock and place bookings a day away, or an hour into an in-progress ride. The exact boundary above was measured by calling the mock with a fixed `Instant`.

Past and in-progress are both "start is not after asOf". The code does not read `endsAt` when deciding. A booking that starts exactly at `asOf` is kept (`isAfter` is strict).

## Crash and failure points

`OutboxPoller.poll` runs `deliver` in one `TransactionTemplate`. The claim is `FOR UPDATE SKIP LOCKED`. Inside that transaction the order is: `beforePublish`, `DeactivationEventConsumer.handle` (dedupe select, booking call, effect insert), `publisher.publish` (log, then listener), then `UPDATE` the outbox to `PUBLISHED`. A `RuntimeException` from handle or publish is caught and `recordFailure` updates the same transaction. An exception from `beforePublish`, or a SQL error that aborts the Postgres transaction, rolls the whole transaction back.

| Point | On redelivery | Proof |
| --- | --- | --- |
| Die before handle | No cancel yet. The row is still `PENDING`, attempts unchanged if the transaction never committed a failure. The next poll cancels once | `OutboxPollerTest.crashAfterCommitBeforePublishStillDeliversOnceToADedupingConsumer` for a throw from `beforePublish` (attempts stay 0). Booking-specific path is the same method |
| Client throws before changing bookings (`failNext`, `timeoutNext`) | Nothing was cancelled. `recordFailure` commits. Attempts increase. Retry performs the cancel once | `bookingFailuresAreRetriedWithoutASecondCancellation`; timeout probe above |
| Client cancels, effect insert throws | In-memory cancel remains. The insert's SQL error aborts the transaction, so the attempt counter rolls back too. Redelivery calls the client again. The mock frees no further seats and records `seats_freed` 0 | Trigger probe. Not in the committed suite |
| Client cancels, effect insert commits, listener or a later step throws | Effect row and the failure update commit together. Redelivery sees `alreadyHandled` and does not call the client | `aCrashAfterCancellationDoesNotCancelAgainOnRedelivery` |
| Process killed after the in-memory cancel and before commit | Database matches the rollback case (pending, attempts 0, no effect). The mock's bookings are gone, so the cancellation is lost. The restarted poller records an empty effect | `docker kill` section |
| Two pollers | The second skips the locked row | Overlap probe and `twoPollersPublishEachRowOnceAndSkipNone` |
| Same event id many times after the effect commit | Client is not called again | Many-redelivery probe |

A seat can be freed twice if the booking client is not idempotent and the transaction rolls back after a successful cancel. The mock is idempotent. The committed tests never call it twice after a success, so that defect stays green. A cancellation is lost when the process dies, because the only booking store is the JVM, and when attempts reach `FAILED`, because nothing replays that row.

While a deactivation is `PENDING`, including during backoff, a later event for that employee is not claimed. Other employees are. After `FAILED`, the guard stops applying. Test settings make that happen on the third failure (`max-attempts: 3`). Production uses 5 attempts, 500 ms base, 30 s cap, with jitter.

## Logs and exceptions added for this phase

| Site | Text |
| --- | --- |
| `DeactivationEventConsumer` | `deactivation effects eventId={} tenantId={} employeeId={} seatsFreed={} cancelledCount={}` |
| `DeactivationEventConsumer` | `IllegalStateException` `"Could not write deactivation effect"` |
| `MockBookingService` | `BookingClientException` `"timeout"` and `"unavailable"` |
| `MockBookingService` | `IllegalArgumentException` `"capacity"`, `"interval"`, `"trip"`, `"times"`, or the id field name (`bookingId`, `tenantId`, `employeeId`, `tripId`) |
| `DemoBookingController` | HTTP 400 `ApiError("INVALID_BOOKING", "Invalid booking seed")`. The handler does not return the exception text |

`OutboxPoller.recordFailure` already logged `outbox publish failed` and `outbox publish exhausted` with event id, tenant id, aggregate id, attempt, and the exception class name. Booking failures use that path. `LoggingEventPublisher` logs ids and `changedFields`, not field values.

## Doc claims the code does not fully back

`docs/DEACTIVATION.md` matches the poller, the payload, the `isAfter` rule, the dedupe row, and the client-throw retry. These sentences are looser than the code:

- "The bodies carry ids and times only." `GET /demo/bookings` also returns `cancelled`, `capacity`, and `occupied`. The live body did not contain a name, email, phone, or pickup.
- "If the process dies after the in-memory cancel but before the transaction commits, the effect row rolls back and the redelivery calls the client again. The client then frees no further seats." That is true while the same JVM keeps the mock (trigger probe: second call, occupied stayed 0). It is not true of `docker kill`: the mock dies too, the cancellation disappears, and the new process records `seats_freed` 0 because it has no bookings.
- The failure section does not say that an effect-insert error aborts the transaction, leaves `attempts` at 0, and makes the eventual effect row report 0 seats.
- Deactivate-before-create is not mentioned. The code emits `EMPLOYEE_DEACTIVATED` and will cancel bookings for that employee id.

`README.md` "Deactivation" matches the processing-time rule, the later create, and the pending-row wait. It describes cancellation as what the service does. The only implementation is `MockBookingService`. The following paragraph does say the demo routes inspect in-memory bookings.

## Demo commands

Default profile, before any demo flag (port 8081 on this run; the branch compose file uses 8080):

```
curl -sS -D - -o /dev/null -X POST http://127.0.0.1:8081/demo/trips \
  -H 'Content-Type: application/json' -d '{"tripId":"trip-1","capacity":4}'
curl -sS -D - -o /dev/null -X POST http://127.0.0.1:8081/demo/bookings \
  -H 'Content-Type: application/json' \
  -d '{"bookingId":"b1","tenantId":"acme","employeeId":"emp-1","tripId":"trip-1","startsAt":"2026-11-02T09:00:00Z","endsAt":"2026-11-02T10:00:00Z"}'
curl -sS -D - -o /dev/null http://127.0.0.1:8081/demo/bookings
```

Each response was `HTTP/1.1 404` with `"error":"Not Found"`. `GET /health` was `{"status":"UP"}`.

Demo profile. Office `BLR` has to exist; there is no office HTTP route, so it was inserted in Postgres. Then:

```
curl -sS -X POST http://127.0.0.1:8081/demo/trips -H 'Content-Type: application/json' \
  -d '{"tripId":"trip-demo","capacity":4}'
curl -sS -X POST http://127.0.0.1:8081/demo/bookings -H 'Content-Type: application/json' \
  -d '{"bookingId":"past","tenantId":"demo-co","employeeId":"emp-1","tripId":"trip-demo","startsAt":"2026-10-01T09:00:00Z","endsAt":"2026-10-01T10:00:00Z"}'
curl -sS -X POST http://127.0.0.1:8081/demo/bookings -H 'Content-Type: application/json' \
  -d '{"bookingId":"future-a","tenantId":"demo-co","employeeId":"emp-1","tripId":"trip-demo","startsAt":"2026-11-02T09:00:00Z","endsAt":"2026-11-02T10:00:00Z"}'
curl -sS -X POST http://127.0.0.1:8081/demo/bookings -H 'Content-Type: application/json' \
  -d '{"bookingId":"future-b","tenantId":"demo-co","employeeId":"emp-1","tripId":"trip-demo","startsAt":"2026-11-03T09:00:00Z","endsAt":"2026-11-03T10:00:00Z"}'
curl -sS http://127.0.0.1:8081/demo/bookings
```

Before deactivation the board had occupied 3 and all three `cancelled: false`.

```
curl -sS -X POST http://127.0.0.1:8081/sync/events -H 'Content-Type: application/json' \
  -d '{"events":[{"tenantId":"demo-co","employeeId":"emp-1","sourceVersion":1,"type":"CREATE","fields":{"name":"Ada","email":"emp-1@example.com","phone":"+15550000001","manager":"M1","officeCode":"BLR","shift":"DAY"}}]}'
curl -sS -X POST http://127.0.0.1:8081/sync/events -H 'Content-Type: application/json' \
  -d '{"events":[{"tenantId":"demo-co","employeeId":"emp-1","sourceVersion":2,"type":"DEACTIVATE","fields":null}]}'
curl -sS http://127.0.0.1:8081/demo/bookings
```

Create returned `APPLIED`. Deactivate returned `APPLIED`. After the poller (about one second) the board had occupied 1, `future-a` and `future-b` `cancelled: true`, `past` `cancelled: false`. Outbox payload `{"employeeId": "emp-1", "changedFields": ["status"]}`, status `PUBLISHED`. Effect row `["future-a", "future-b"]`, `seats_freed` 2.

`POST /demo/failures` returned 404. There is nothing to turn on in the video to make the booking client fail.

`docker kill` used a separate container whose jar slept 20 seconds after the cancel and before the effect insert. That sleep is not in the branch. During the sleep, `GET /demo/bookings` already showed `k-a` and `k-b` cancelled and `k-past` not, while `outbox` was `PENDING` attempts 0 and `deactivation_effect` was empty. After `docker kill` the row was still `PENDING` attempts 0. The restarted unmodified app published it as `seats_freed` 0 and `[]`, and its board was empty.

## Gaps

Phase 6 cancels bookings only inside `MockBookingService`. There is no booking database, no remote client, and no seat that survives a process restart. A deactivated employee with no in-memory bookings is a no-op that still writes a zero effect row. A `FAILED` outbox row is kept and is not replayed, so those future bookings stay booked. Deactivate-before-create does cancel future bookings for that employee id, which the deactivation doc does not mention. Failure injection cannot be shown over HTTP.

## Top risks

| Risk | Verdict | Fix |
| --- | --- | --- |
| The booking store is the JVM. `docker kill` after a successful cancel loses the cancellation, and the redelivery records zero seats | FAIL | Persist bookings, and make cancel idempotent on the outbox event id |
| A non-idempotent client frees a seat twice when the database transaction rolls back after a successful cancel. The suite stays green | FAIL | Extend `cancelFutureBookings` with the event id and commit a test that fails the effect insert, then asserts the second call frees 0 seats |
| A `FAILED` deactivation is never retried. A later create for that employee publishes, and the bookings stay | PARTIAL | Add an operator replay that sets that outbox row back to `PENDING` |
| The tenant filter has no test. Dropping it left 10 tests green | PARTIAL | Seed the same employee id in a second tenant inside `deactivationCancelsFutureBookingsOnly` |
| Cancellation scans every booking in the process. At 100,000 bookings that was 46 ms and there is no index to EXPLAIN | PARTIAL | Store bookings and index `(tenant_id, employee_id, starts_at)` |

## Tree

Temporary test `Phase6AuditProbeTest` was deleted. Mutation edits were restored with `git checkout`. This file is the remaining change.
