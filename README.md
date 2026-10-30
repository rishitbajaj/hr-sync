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

## Security

Callers sign in at `POST /auth/token` and send the returned JWT on later requests. Roles are `EMPLOYEE`, `ADMIN`, and `OPERATOR`. An endpoint with no explicit rule is denied. An admin or an employee can act only in the tenant named in the token. An operator is platform staff and must name the tenant when a rule lets them cross tenants. The role table, the token claims, and the trade-offs are in `docs/SECURITY.md`. The signing secret is `HR_SYNC_JWT_SECRET` (at least 32 bytes, no default).

On an empty `users` table, startup creates one operator (tenant `platform`) and one admin from `HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME`, `HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD`, `HR_SYNC_BOOTSTRAP_ADMIN_USERNAME`, `HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD`, and `HR_SYNC_BOOTSTRAP_ADMIN_TENANT`. Passwords have no default and are stored as BCrypt hashes. A restart does not create them again. `docker compose` reads those variables from `.env`.

The office code in the ingest body must already exist for that tenant. Then:

```bash
curl -s -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"the-admin-password-from-env"}'

curl -s -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"events":[{"tenantId":"acme","employeeId":"emp-1","sourceVersion":1,"type":"CREATE","fields":{"name":"Ada","email":"ada@example.com","phone":"+15550000001","manager":"M1","officeCode":"BLR","shift":"DAY"}}]}'

curl -s http://localhost:8080/employees/emp-1 \
  -H "Authorization: Bearer $TOKEN"
```

`$TOKEN` is the `token` field from the login response. `acme` is `HR_SYNC_BOOTSTRAP_ADMIN_TENANT`, and `admin` is `HR_SYNC_BOOTSTRAP_ADMIN_USERNAME`.

## Caching

Office existence and `GET /employees/{id}` are cached in memory with Caffeine. Every key includes `tenant_id`. Office entries live at most 60 seconds and employee entries at most 10 seconds (`hr-sync.cache.office-ttl`, `hr-sync.cache.employee-ttl`), with a maximum of 10,000 entries each. An unknown office is not stored. A missing employee and a `PLACEHOLDER` row are not stored. Hit and miss counts are `cache_gets_total`.

Version comparison, ownership, and idempotency always read the database. Those decisions do not use the cache.

What can be stale: on the node that applied a change or a deactivation, that employee's cache entry is removed before the call returns, so the next read on that node sees the write. On any other node, an employee read can be stale for at most 10 seconds. A cached office that still exists can be served for at most 60 seconds after it is removed; a newly added office is looked up on the next check because a miss is not cached.

## Error handling

Every error body is `code`, `message`, and `correlationId`. It does not include a stack trace, SQL, a class name, or personal data. The statuses are validation `400`, malformed JSON `400`, unsupported media type `415`, method not allowed `405`, unknown route `404`, unauthenticated `401`, forbidden `403`, request too large `413`, conflict `409`, database unavailable or pool exhausted `503` with `Retry-After`, and any other failure `500` with the message `An unexpected error occurred`. Connection, statement, and socket timeouts default to about 3 seconds (`hr-sync.database.connection-timeout`, `hr-sync.database.statement-timeout`).

A paused Postgres on a throwaway container (port 5436, app on 8086, so the audit stack on 5432 and 8080 was left alone) returned `503` in 3.094 seconds. After a warm `CREATE` of `emp-probe` at version 1 had already returned `200` (`time_total=0.381972`, outcome `APPLIED`), the same body was sent again:

```bash
docker pause hrsync-error-probe-pg
curl -sS -D /tmp/paused-headers.txt -o /tmp/paused-body.txt \
  -w 'http_code=%{http_code} time_total=%{time_total}\n' --max-time 20 \
  -X POST http://localhost:8086/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: probe-paused' \
  -d '{"events":[{"tenantId":"acme","employeeId":"emp-probe","sourceVersion":1,"type":"CREATE","fields":{"name":"Probe","email":"probe@example.com","phone":"+15550000001","manager":"M1","officeCode":"BLR","shift":"DAY"}}]}'
docker unpause hrsync-error-probe-pg
```

While paused:

```text
HTTP/1.1 503
Retry-After: 3
X-Correlation-Id: probe-paused

{"code":"UNAVAILABLE","message":"Database is unavailable","correlationId":"probe-paused"}
http_code=503 time_total=3.094185
```

After `docker unpause`, the same ingest with `X-Correlation-Id: probe-recovered` returned `200` (`time_total=0.011950`) and `{"results":[{"tenantId":"acme","employeeId":"emp-probe","sourceVersion":1,"outcome":"IGNORED","reasonCode":"DUPLICATE"}]}`.

## Load

k6 runs in the `grafana/k6` image (`k6 v2.3.0`). The stack is `load/docker-compose.yml` (project `hrsync-load`, host ports 5442 and 8092). One line, from the repo root, after the compose file is up and `python3 load/generate.py` has seeded:

```bash
docker run --rm --network hrsync-load --env-file load/.env -e SCENARIO=ingest -e BASE_URL=http://app:8080 -v "$PWD:/work" -w /work grafana/k6 run load/k6.js
```

`./load/run.sh ingest` is that command plus CPU, `pg_stat_activity`, and actuator samples, then `load/invariants.py`. The ramp was `./load/run.sh ramp -e STAGE=15s -e RAMP_EXEC=ingest`. The four fixed runs were `./load/run.sh <scenario>` with `VUS=8`, `WARMUP=10s`, `DURATION=60s`, in the order reads, contention, mixed, ingest, on one database.

Machine: WSL2 (`Linux 6.6.87.2-microsoft-standard-WSL2`), AMD Ryzen 7 7840HS (8 cores, 16 threads), 7,760,064 kB RAM, Docker 29.4.2, Compose v5.1.3, Temurin 21.0.13, PostgreSQL 16.15 (`postgres:16-alpine` in `hrsync-load-pg`). Measured on 2026-10-30. No code was changed to improve these numbers.

Hikari is left at its default. `hikaricp.connections.max` was 10 on every sample. Each event is its own transaction: `pg_stat_statements` for the 60s ingest counted 127,320 `BEGIN`s, 100,740 employee updates (mean 0.06 ms), 100,740 outbox inserts, and 100,740 `processed_event` inserts. A batch of 100 therefore holds one pool connection for the whole request.

The ramp is 15s at each virtual-user count, ingest only. HTTP error rate was 0. Throughput is requests in that 15s divided by 15. Events/s is that times 100. Latency is the stage trend.

| VUs | req/s | events/s | p50 | p95 | p99 |
| --- | --- | --- | --- | --- | --- |
| 2 | 4.93 | 493 | 414.1 ms | 450.9 ms | 585.5 ms |
| 4 | 9.60 | 960 | 415.6 ms | 437.4 ms | 448.0 ms |
| 8 | 18.2 | 1,820 | 440.8 ms | 467.5 ms | 511.4 ms |
| 16 | 21.4 | 2,140 | 760.5 ms | 1,021.2 ms | 1,171.0 ms |
| 32 | 22.8 | 2,280 | 1,440.8 ms | 2,134.4 ms | 2,452.3 ms |
| 48 | 23.27 | 2,327 | 2,098.4 ms | 3,469.6 ms | 4,046.1 ms |

It stops scaling after 8 virtual users. From 8 to 16, throughput rose by 3.2 req/s while p50 rose from 441 ms to 761 ms. From 32 to 48 it rose from 22.8 to 23.3 req/s while p50 rose from 1,441 ms to 2,098 ms. The ceiling is about 23 requests/s (about 2,300 events/s). Ten connections each busy for ~420 ms can finish about 24 batches/s, which is the same ceiling. During the ramp, Docker's CPU sample peaked at 125.6% for the app and 312.8% for Postgres. Peak container RSS was 541.2 MiB (app) and 252.3 MiB (Postgres). The ramp did not record a JVM heap gauge.

The 60s runs, steady window only for latency and for the request rate. Error rate was 0 on every run. CPU and RSS are the max Docker sample. Heap is the max untagged `jvm.memory.used` sample (heap and non-heap together). The actuator `process.cpu.usage` samples in the same windows stayed under 0.33, so the table uses the Docker reading.

| Scenario | req/s | events/s | p50 | p95 | p99 | app CPU | Postgres CPU | app RSS | heap |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Ingest, 8 VUs | 19.07 | 1,907 | 418.7 ms | 454.5 ms | 604.4 ms | 102.2% | 254.4% | 632.6 MiB | 345,372,256 |
| Contention, 8 VUs | 14.27 | 1,427 | 500.3 ms | 924.3 ms | 1,653.8 ms | 75.3% | 246.5% | 611.2 MiB | 331,682,728 |
| Reads, warm | 8,893 | — | 0.321 ms | 0.492 ms | 0.737 ms | 514.1% | 82.6% | 601.6 MiB | 305,513,848 |
| Reads, cold | — | — | 0.695 ms | 1.318 ms | 2.189 ms | (same run) | (same run) | (same run) | (same run) |
| Mixed reads | 8,124 | — | 0.357 ms | 0.522 ms | 0.759 ms | 354.7% | 188.8% | 630.0 MiB | 336,181,984 |

Cold reads completed 49,996 requests and then ran out of ids (`cold_exhausted` 196), so there is no 60s cold rate. `pg_stat_statements` recorded 50,442 employee selects in that run against 533,600 warm reads, so the warm path was the cache. Locking one employee row is an index scan on `uq_employee_tenant_employee` (cost 0.42..8.45). During contention, `pg_stat_activity` showed sessions waiting on `Lock` / `tuple` and `Lock` / `transactionid`, and on `WALWrite`.

Mixed ingest was 636 batches (63,600 events) across the 10s warmup and the 60s hold, about 9 batches/s from 4 writer virtual users. 487,430 of the 487,974 steady requests were reads, so the mixed latency above is the read latency. Hikari active connections peaked at 9 (pending 0) on ingest and contention, and at 2 on reads.

The outbox poller is the limit that falls over. It claims 100 rows and then waits 1s. The 60s ingest applied 100,740 changes in the whole run, about 1,400 applied events/s, against a poller that later published a 34k queue at about 60 rows/s. After contention the queue was 85,672 pending and a further 120s published almost none of them. Mixed and ingest then sat at 133,516 and 234,256 pending with the count unchanged for 20s. The pending-count plan on that table was a parallel index-only scan (cost about 5,937). The claim itself turns hash and merge joins off. Saved Prometheus scrapes were `# EOF` (6 bytes) or empty; the JSON gauges above came from `/actuator/metrics`.

## Verification

`mvn -B test` on 2026-10-30: 181 tests, 0 failures, plus the separate benchmark. That benchmark printed `reconciliation benchmark rows=200000 elapsedMs=2571 peakHeapBytes=86820712 maxHeapBytes=268435456`.

## Known limitations

Logs may include personal field names (`name`, `email`, `phone`, `pickupPoint`, and the other names in `changedFields`). They do not include the values.

A mixed diff of about 5% of 200,000 rows takes about 36 s (36.217 s on this machine) because each create, update, and deactivation is its own transaction. The hash diff itself, on an identical 200,000-row export, takes about 2 s (1.814 s and 2.217 s).
