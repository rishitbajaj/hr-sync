# hr-sync

HR roster sync: idempotent, out-of-order, self-healing employee records.

## Reconciliation benchmark

Seeding the store is not included in the time. Peak heap is the highest `MemoryMXBean` heap used, sampled every 5 ms during the diff, after a garbage collection that followed the seed. A heap pool's own peak is used when it is higher than those samples. The identical 200,000-row row is `ReconciliationBenchmarkTest`, excluded from the default Surefire run and executed with `mvn surefire:test@reconciliation-benchmark` (`-Xmx256m`, `Runtime.maxMemory` 268,435,456 bytes). The other rows used the same measurement on 2026-10-30. The 400,000-row run and the mixed run were a temporary test, not part of the suite.

| Scenario | Rows | Heap cap | Elapsed | Peak heap | Result |
| --- | --- | --- | --- | --- | --- |
| Identical export | 200,000 | 256 MiB | 1.814 s (1,814 ms) | 136,320,024 bytes (130 MiB) | pass |
| Identical export, clean-clone rerun | 200,000 | 256 MiB | 2.217 s (2,217 ms) | 131,999,968 bytes | pass |
| Mixed diff, about 5% (6,000 updates, 2,000 creates, 2,000 missing) | 200,000 | 256 MiB | 36.217 s (36,217 ms) | 146,011,416 bytes | pass |
| Identical export | 400,000 | 256 MiB | 4.620 s (4,620 ms) | 135,028,456 bytes | pass |
| Identical export | 200,000 | 128 MiB | 1.388 s (1,388 ms) | 99,754,912 bytes | pass |
| Identical export, lowest cap that passed | 200,000 | 48 MiB | 1.910 s (1,910 ms) | 46,186,664 bytes | pass |
| Identical export | 200,000 | 40 MiB | — | — | fail: forked JVM `Java heap space` before the test started |
| Identical export | 200,000 | 32 MiB | — | — | fail: `OutOfMemoryError` creating `entityManagerFactory` |

The 400,000-row peak (135,028,456 bytes) does not double the 200,000-row peaks (136,320,024 and 131,999,968 bytes). Caps of 96, 80, and 64 MiB also passed, with peaks of 87,010,048, 77,192,240, and 60,712,400 bytes. The lowest cap that completed the identical 200,000-row diff is 48 MiB (`Runtime.maxMemory` 50,331,648 bytes).

An identical diff is one streaming pass over the export and one over the active store, plus 256 bucket digests. Time is linear in the two sizes. Memory stays at the bucket table while every bucket matches. A bucket is the employee id hash modulo 256, so a scattered change dirties essentially every bucket. The drill-in then holds a head (id, version, record hash) for every active employee in a dirty bucket, and each create, update, and deactivation is its own transaction. That apply path is where a real nightly diff stops scaling: the 5% mix above took 36.2 s, against 1.8–2.2 s for an identical 200,000-row export.

Machine: WSL2 (`Linux romrom 6.6.87.2-microsoft-standard-WSL2`), AMD Ryzen 7 7840HS (8 cores, 16 threads), 7,760,064 kB RAM, Docker Desktop 29.4.2, Temurin 21.0.13+9, PostgreSQL 16.15 (`postgres:16-alpine` via Testcontainers). Measured on 2026-10-30.

## Retention

A scheduled job deletes `PUBLISHED` outbox rows and `processed_event` rows older than `hr-sync.retention.age`, `hr-sync.retention.batch-size` rows at a time. `FAILED` outbox rows stay so an operator can inspect them. `PENDING` rows stay until they are published or fail.

After a `processed_event` row is pruned, replaying that version does not apply it again. The stored channel version is still compared, and a version that is not newer is `STALE_IGNORED`. That version comparison is the backstop once the idempotency row is gone.

## Deactivation

Deactivating an employee writes an `EMPLOYEE_DEACTIVATED` outbox event. When that event is delivered, future cab bookings for that employee are cancelled and their seats are freed. A booking is future when it starts after the deactivation was recorded. Past and in-progress bookings stay. A later create does not restore cancelled bookings. If the booking client fails, the outbox retry path redelivers the event; that employee's later events wait while a deactivation for them is still pending or failed, and other employees do not. The rules are in `docs/DEACTIVATION.md`.

With the `demo` profile, `POST /demo/trips`, `POST /demo/bookings`, and `GET /demo/bookings` seed and inspect the in-memory bookings.

## Known limitations

Logs may include personal field names (`name`, `email`, `phone`, `pickupPoint`, and the other names in `changedFields`). They do not include the values.

A mixed diff of about 5% of 200,000 rows takes about 36 s (36.217 s on this machine) because each create, update, and deactivation is its own transaction. The hash diff itself, on an identical 200,000-row export, takes about 2 s (1.814 s and 2.217 s).
