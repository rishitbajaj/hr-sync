# hr-sync

HR roster sync: idempotent, out-of-order, self-healing employee records. Postgres holds the roster, the idempotency rows, and the outbox. The design is in `docs/DESIGN.md`.

## Run

Copy the example env file and set every value. `HR_SYNC_JWT_SECRET` must be at least 32 bytes. There is no default for the secret or the passwords. Compose will not start with an empty value. A variable already exported in the shell wins over `.env`.

```bash
cp .env.example .env
docker compose up --build -d
```

The app listens on port 8080 and Postgres on port 5432. Compose also starts a single-node Kafka in KRaft mode, creates the topic `hr-sync.employee-changes` with 6 partitions, and sets the app publisher to `kafka`. The app waits until that broker is healthy. Host consumers use `localhost:9092`. The app uses `kafka:19092` on the compose network. `GET /health` is open and returns 200 when the process is up. Set `SPRING_PROFILES_ACTIVE=demo` in `.env` before that command when you want the booking endpoints below. There is no HTTP API for offices. Insert one for the admin tenant:

```bash
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "insert into office (id, tenant_id, code) values (gen_random_uuid(), '${HR_SYNC_BOOTSTRAP_ADMIN_TENANT}', 'BLR') on conflict (tenant_id, code) do nothing;"
```

Run that from a shell that has sourced `.env`, or substitute the tenant yourself.

An applied change is one Kafka message. The key is `tenantId:employeeId`, so one employee's events stay in one partition. The JSON value is `eventId`, `tenantId`, `employeeId`, `eventType`, `changedFields` (names only), `sourceVersion`, `sequence` (the outbox id), `occurredAt`, and `schemaVersion`. The header is `correlationId` when the request had one. The message has no phone, email, name, or pickup point. A stale, duplicate, or rejected change produces none. The row is marked published only after the broker acks. Ingest does not wait on Kafka. If the broker is down, the batch still returns 200 and the outbox stays pending until the broker is back.

Read the topic from the host, drop duplicate event ids, and check per-employee order. The script prints counts only.

```bash
./scripts/kafka-demo-consumer.sh
```

`HR_SYNC_KAFKA_BOOTSTRAP_SERVERS` defaults to `localhost:9092` and `HR_SYNC_KAFKA_TOPIC` to `hr-sync.employee-changes`. The line looks like `read=8 unique=8 duplicates=0 orderViolations=0 malformed=0 stale=0`. A message whose `sourceVersion` is older than the highest version already accepted for that key, and whose `sequence` is also older, is counted in `stale` and dropped. A second copy of the same `eventId` is a duplicate. A broken order or an unreadable record exits 1. A stale count does not. A `FAILED` outbox row of any event type blocks later events for that employee until an operator replays or discards it.

## Walkthrough

`TENANT` is `HR_SYNC_BOOTSTRAP_ADMIN_TENANT`. The stored tenant is the token's tenant. A body `tenantId` that differs is 403 and writes nothing. An applied result omits `reasonCode`.

```bash
set -a; source .env; set +a
TENANT="$HR_SYNC_BOOTSTRAP_ADMIN_TENANT"
admin_token() {
  curl -sS -X POST http://localhost:8080/auth/token \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${HR_SYNC_BOOTSTRAP_ADMIN_USERNAME}\",\"password\":\"${HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD}\"}"
}
operator_token() {
  curl -sS -X POST http://localhost:8080/auth/token \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME}\",\"password\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD}\"}"
}
TOKEN=$(admin_token | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
OP=$(operator_token | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
```

Login returns `token` and `expiresInSeconds` (900 at the 15 minute default). A bad password, a missing token, and an expired token are 401 `UNAUTHORIZED`. The wrong role is 403.

A batch may mix a bad record with a good one. The bad phone is rejected in place. The create still applies. Posting that create again is `DUPLICATE`.

```bash
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-bad\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Bad\",\"email\":\"bad@example.com\",\"phone\":\"12345\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}},
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}
  ]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
```

The first result is `REJECTED` / `INVALID_PHONE`. The second event in that batch is `APPLIED`. The repeat is `IGNORED` / `DUPLICATE`.

Version 3 then version 2. The batch is stored in version order when both are still newer. Sent after version 3 has committed, version 2 is `IGNORED` / `STALE_IGNORED`, and `hr_version` stays 3.

```bash
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":3,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Three\",\"shift\":\"NIGHT\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":2,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Two\",\"shift\":\"DAY\"}}]}"
curl -sS http://localhost:8080/employees/walk-1 -H "Authorization: Bearer $TOKEN"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, shift from employee where tenant_id = '$TENANT' and employee_id = 'walk-1';"
```

`GET /employees/walk-1` returns `status`, `officeCode`, and `shift`. It does not return the name or the version. `shift` is `NIGHT`. The SQL row is name `Ada Three`, `hr_version` 3, shift `NIGHT`.

Field ownership. A transport event may set pickup and assistance. An HR event that also sends those values does not write them, and does not change `transportVersion`.

```bash
curl -sS -X POST http://localhost:8080/sync/transport-events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"UPDATE\",\"fields\":{\"pickupPoint\":\"Gate 4\",\"specialAssistance\":\"wheelchair\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":4,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Owned\",\"pickupPoint\":\"Gate 9\",\"specialAssistance\":\"none\"}}]}"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, pickup_point, transport_version from employee where tenant_id = '$TENANT' and employee_id = 'walk-1';"
```

The SQL row is name `Ada Owned`, `hr_version` 4, `pickup_point` `Gate 4`, `transport_version` 1.

Reconciliation. The file omits `walk-1`, so the missing ratio is above 0.30. Rows that are in the file are applied. Missing employees are not deactivated until an operator approves. The CSV has nine columns: `tenantId,employeeId,sourceVersion,name,email,phone,manager,officeCode,shift`.

```bash
printf '%s\n' 'tenantId,employeeId,sourceVersion,name,email,phone,manager,officeCode,shift' \
  "$TENANT,walk-new,1,New,new@example.com,+15550000002,M1,BLR,DAY" > /tmp/truncated.csv
curl -sS -X POST "http://localhost:8080/reconciliation/runs?tenantId=$TENANT" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/csv' \
  --data-binary @/tmp/truncated.csv
```

The body has `status` `HALTED` and `haltReason` `MISSING_RATIO_EXCEEDED`. Copy `id`, then:

```bash
curl -sS -X POST "http://localhost:8080/reconciliation/runs/$RUN/approve?tenantId=$TENANT" \
  -H "Authorization: Bearer $OP"
```

`$OP` is the operator token. An admin receives 403. The status becomes `APPROVED`. `walk-1` is `DEACTIVATED` when its `hrVersion` is still the version saved with the halt. A second run while one is `RUNNING` is 409.

Deactivation effects need `SPRING_PROFILES_ACTIVE=demo` and a restart so `/demo/**` is registered. Seed a future trip, deactivate, and read the board. The poller publishes within its one-second interval.

```bash
curl -sS -X POST http://localhost:8080/demo/trips \
  -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT\",\"tripId\":\"trip-walk\",\"capacity\":4}"
curl -sS -X POST http://localhost:8080/demo/bookings \
  -H 'Content-Type: application/json' \
  -d "{\"bookingId\":\"book-walk\",\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"tripId\":\"trip-walk\",\"startsAt\":\"2026-12-01T09:00:00Z\"}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Rider\",\"email\":\"rider@example.com\",\"phone\":\"+15550000003\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"sourceVersion\":2,\"type\":\"DEACTIVATE\"}]}"
curl -sS http://localhost:8080/demo/bookings
```

The booking's `cancelled` is true after the outbox row is published. On a fresh stack the `GET` in the block above still returned `"cancelled":false` in the same second as the deactivate, then `"cancelled":true` on a read two seconds later. Read the board again after the one-second interval before treating a false value as a failure. `POST /demo/failures` with `{"failures":1}` makes the next cancel throw. That outbox row stays pending, then `FAILED` after 5 attempts, and blocks later events for that employee. `GET /outbox/failed?tenantId=$TENANT` with the operator token lists `employeeId`, `eventId`, `attempts`, and `reasonCode`. `POST /outbox/{eventId}/replay?tenantId=$TENANT` sets it back to pending.

`GET /actuator/prometheus` with the operator token and `Accept: text/plain` returns the scrape. An admin receives 403.

## Troubleshooting

`password authentication failed` or a Flyway error that cannot log in as `hrsync` usually means Postgres is using a volume from an earlier run. That volume already has a password, and a new `POSTGRES_PASSWORD` does not replace it. Remove the volume and start again:

```bash
docker compose down -v
docker compose up --build -d
```

`down -v` deletes that project's database. A leftover `COMPOSE_PROJECT_NAME` selects a different Compose project, and with it a different volume, so the same login error can appear even when `.env` is new. Unset it before `up` when this directory should be its own project.

While the app is still starting, `GET /health` can reset the connection for a few seconds. It returns 200 `{"status":"UP"}` shortly after.

`GET /demo/bookings` can still show `"cancelled":false` until the outbox poller publishes the deactivation. The poll interval is one second. Read the board again after that interval.

## Assumptions

One app process, one Postgres, and one Kafka broker. Offices exist before an HR event names them. The admin acts only in the bootstrap tenant. Tokens are HS256 JWTs that last 15 minutes. Bookings live in the same database, schema `booking_svc`, and each cancel commits on its own. The outbox is still the source of truth. Compose publishes to Kafka after the in-process deactivation effect. Tests use the logging publisher unless they start Kafka. The age gauge has one-second resolution.

## API

| Method | Path | Who | Role |
| --- | --- | --- | --- |
| GET | `/health` | anyone | liveness |
| POST | `/auth/token` | anyone | returns `token`, `expiresInSeconds` |
| POST | `/sync/events` | admin | HR batch. Body `{"events":[...]}`. `200` with `{"results":[...]}`, or `503` when a record is retryable |
| POST | `/sync/transport-events` | admin | transport batch. Type must be `UPDATE`. Same outcomes as `/sync/events` |
| GET | `/employees/{id}` | employee (self), admin, operator with `tenantId` | `tenantId`, `employeeId`, `status`, `officeCode`, `shift`. Placeholders are not returned |
| POST | `/reconciliation/runs?tenantId=` | admin | `text/csv` export |
| GET | `/reconciliation/runs/{id}?tenantId=` | admin or operator | run status |
| POST | `/reconciliation/runs/{id}/approve?tenantId=` | operator | apply a halted deactivation set. A `SUPERSEDED` run is 409 with code `SUPERSEDED` |
| GET | `/outbox/failed?tenantId=` | operator | failed rows |
| POST | `/outbox/{id}/replay?tenantId=` | operator | failed row back to pending |
| POST | `/outbox/{id}/discard?tenantId=` | operator | body `{"reason":"..."}`, failed row only |
| GET | `/actuator/prometheus` | operator | `Accept: text/plain` |
| POST, GET | `/demo/**` | anyone, demo profile only | trips, bookings, failure injection |

A new reconciliation run marks every older `HALTED` run for that tenant `SUPERSEDED`. Approving that older run deactivates nobody. Approving the latest halted run still applies once. A halted run for another tenant stays `HALTED`.

Outcomes on both ingest paths:

| Outcome | Reason | HTTP | What to do |
| --- | --- | --- | --- |
| `APPLIED` | omitted | 200 | The change is stored. Sending it again is `DUPLICATE` |
| `REJECTED` | `INVALID_PHONE`, `INVALID_EMAIL`, `INVALID_SHIFT`, `UNKNOWN_OFFICE`, `MISSING_REQUIRED_FIELD` | 200 | Fix the record. The rest of the batch is still processed |
| `REJECTED` | `APPLY_FAILED` | 200 | That record violated a data constraint. The rest of the batch is still processed. Retrying the same values fails the same way |
| `IGNORED` | `DUPLICATE` | 200 | This version was already stored. Nothing new is written |
| `IGNORED` | `STALE_IGNORED` | 200 | A newer version is already stored. Nothing new is written |
| `RETRYABLE_ERROR` | `DATABASE_UNAVAILABLE` | 503, `Retry-After` | The database, a connection, or a statement timeout stopped this record. It was not stored. Send the batch again after the delay |
| `RETRYABLE_ERROR` | `NOT_PROCESSED` | 503, `Retry-After` | The server stopped before this record because an earlier one could not reach the database. Send the batch again |

If any record is `RETRYABLE_ERROR`, the status is 503 and the body also has `correlationId`. Records already `APPLIED` in that body stay applied.

The role table is in `docs/SECURITY.md`. Conflict and idempotency rules are in `docs/CONFLICT_RULES.md`. Deactivation rules are in `docs/DEACTIVATION.md`. Operations and alerts are in `docs/OPERATIONS.md`. The claim stall is in `docs/OUTBOX_CLAIM.md`. The load harness is in `load/README.md`.

## Tests

Java 21. From the repo root:

```bash
mvn -B test
```

That runs the tests and then the reconciliation benchmark in a forked JVM with `-Xmx256m`. The benchmark is `ReconciliationBenchmarkTest`. It is excluded from the default Surefire execution and included again as execution `reconciliation-benchmark`.

Load tests use `load/docker-compose.yml` (ports 5442 and 8092) and are described in `load/README.md`.

`python3 scripts/generate-exports.py` writes a 200,000-row export and a 120,000-row prefix under `scripts/out/`. `scripts/demo.sh` runs the walkthrough above and prints each response. It reads `.env` and talks to `BASE_URL` (default `http://localhost:8080`). `docs/DEMO.md` is the timed version of that walkthrough, including the kill and the paused database.

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

With the `demo` profile, `POST /demo/trips`, `POST /demo/bookings`, `GET /demo/bookings`, and `POST /demo/failures` seed and inspect bookings. The rows are stored in Postgres schema `booking_svc`. The profile also inserts users `operator`, `admin`, and `employee`, all with password `demo-password`.

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

An error body is `code`, `message`, and `correlationId`. It does not include a stack trace, SQL, a class name, or personal data. The statuses are validation `400`, malformed JSON `400`, unsupported media type `415`, method not allowed `405`, unknown route `404`, unauthenticated `401`, forbidden `403`, request too large `413`, conflict `409`, and any other failure `500` with the message `An unexpected error occurred`. A database failure on a read is `503` with `Retry-After` and that error body. A database failure during ingest is also `503` with `Retry-After`, and the body is the per-record results plus `correlationId` (`docs/DEMO.md`). Connection, statement, and socket timeouts default to about 3 seconds (`hr-sync.database.connection-timeout`, `hr-sync.database.statement-timeout`).

A paused Postgres, with the app on port 8087 so the audit stack on 5432 and 8080 was left alone, returned `503` for `GET /employees/walk-missing` in 3.138 seconds (`Retry-After: 3`, body `UNAVAILABLE`). The three-create batch in `docs/DEMO.md` returned `503` in 3.247 seconds with `RETRYABLE_ERROR` results and stored nothing. The same batch after unpause returned `200` in 0.055 seconds and each employee was stored once.

## Load

k6 runs in the `grafana/k6` image (`k6 v2.3.0`). The stack is `load/docker-compose.yml` (project `hrsync-load`, host ports 5442, 8092, and 9094). One line, from the repo root, after the compose file is up and `python3 load/generate.py` has seeded:

```bash
docker run --rm --network hrsync-load --env-file load/.env -e SCENARIO=ingest -e BASE_URL=http://app:8080 -v "$PWD:/work" -w /work grafana/k6 run load/k6.js
```

`./load/run.sh ingest` is that command plus CPU, `pg_stat_activity`, and actuator samples, then `load/invariants.py`. The ramp was `./load/run.sh ramp -e STAGE=15s -e RAMP_EXEC=ingest`. The four fixed runs were `./load/run.sh <scenario>` with `VUS=8`, `WARMUP=10s`, `DURATION=60s`, in the order reads, contention, mixed, ingest, on one database.

Machine: WSL2 (`Linux 6.6.87.2-microsoft-standard-WSL2`), AMD Ryzen 7 7840HS (8 cores, 16 threads), 7,760,064 kB RAM, Docker 29.4.2, Compose v5.1.3, Temurin 21.0.13, PostgreSQL 16.15 (`postgres:16-alpine` in `hrsync-load-pg`). Measured on 2026-10-30. The ramp and the four runs below used the poller that claimed one row per employee.

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

### Correctness under load

`./load/run.sh` runs `python3 load/invariants.py check <scenario>` after the minute. The four checks below are that run. Reads was a fresh published seed. Contention, mixed, and ingest followed on the same database, so a pending count is the sum of what earlier runs had not published.

| Check | Reads | Contention | Mixed | Ingest |
| --- | --- | --- | --- | --- |
| applied + stale + duplicate + rejected | 0 | 86,508 + 6,292 + 0 + 0 = 92,800 | 47,860 + 6,200 + 6,360 + 3,180 = 63,600 | 100,740 + 13,160 + 13,400 + 6,700 = 134,000 |
| events sent | 0 | 92,800 | 63,600 | 134,000 |
| processed-event rows added | 0 | 86,508 | 47,860 | 100,740 |
| outbox rows added | 0 | 86,508 | 47,860 | 100,740 |
| version mismatches | 0 | 0 | 0 | 0 |
| cross-tenant rows | 0 | 0 | 0 | 0 |
| read leaks | 0 | 0 | 0 | 0 |
| ended PUBLISHED | yes | no | no | no |

Every answered event was one of those four outcomes, and the HTTP failure count was 0. Each applied change added one `processed_event` row and one outbox row. Stale, duplicate, and rejected events added neither. No employee `hr_version` disagreed with the highest applied version stored for it. No load-a row carried the load-b office or name, and no load-b row carried load-a's. The read scenario also checked the body (`officeCode`, `tenantId`, `status`) and counted 0 leaks.

Reads finished with nothing pending. The drain call returned in 0.293s and the oldest-pending age was 0.

The other three did not finish PUBLISHED. Contention published 836 of its 86,508 new rows; after a 120s wait, 85,672 were still `PENDING`, none `FAILED`, and the oldest was 196s. Mixed added 47,860 and published 16; the drain stopped at 21.6s because the pending count (133,516) had not moved, and the oldest was 292s. Ingest added 100,740 and published none; the drain stopped at 21.9s with 234,256 pending and the oldest at 395s. The roster checks still passed. The failed check is the outbox reaching `PUBLISHED`.

### Killed during ingest

The database above was discarded first (`docker compose --env-file load/.env -f load/docker-compose.yml down -v`, then `up -d` and `python3 load/generate.py`), because that outbox was already stuck. The kill run was:

```bash
DRAIN_TIMEOUT=120 VUS=8 WARMUP=10s DURATION=60s WARMUP_SECONDS=10 ./load/fail-under-load.sh
```

That starts the ingest scenario, waits 25s, runs `docker kill hrsync-load-app`, lets k6 finish against the dead process, then `docker start hrsync-load-app` and checks invariants with `--allow-unacked`. A killed batch can commit rows the client never reads, so the database may show more applies than the responses do.

Of 110,600 events sent, 44,800 came back in a response and 65,800 did not (the requests after the kill). The responses add up: 33,920 applied + 4,160 stale + 4,480 duplicate + 2,240 rejected = 44,800. The database added 34,221 `processed_event` rows and 34,221 outbox rows, 301 more applies than the client was told about. Version mismatches were 0. Cross-tenant rows were 0. Every load employee still had a processed row.

The 120s drain after restart left 25,665 pending (none failed) and an oldest age of 199s, so that check did not yet pass `PUBLISHED`. The gauge then fell from 23,365 pending to 0 in 377s. From the first post-restart sample (33,965 pending, oldest 81s) to pending 0 was 529s. The oldest age reached 571s on the sample before the queue emptied, then read 0. A second check after that returned pending 0, oldest 0, the same row counts, and no failures.

### After the per-employee claim

The stall was the claim. With eight employees, the ordering guard left eight claimable heads. The poll asked for 100 rows with hash and merge joins turned off, the nested-loop anti-join exceeded the 3 second statement timeout, and the transaction rolled back. Pending stayed put. There were no `FAILED` rows and no blocked head. The plans and the frozen `outbox.published` counter are in `docs/OUTBOX_CLAIM.md`.

The poller now locks each employee's oldest pending row (`FOR UPDATE SKIP LOCKED`) and, in that same transaction, the following pending rows of that employee up to `employee-cap`, publishes that run in id order, and marks it with one `id = ANY(...)` update. A failure stops the rest of that employee's run. Other employees continue. A full batch is polled again immediately until `tick-budget` is spent. Another poller still cannot publish a later row while an earlier one is pending or in flight.

Defaults: the head lookup averaged 1.81 ms during the ingest minute below, a full batch is 400 rows, and two publisher threads fit the pool of 10.

| Setting | Value |
| --- | --- |
| `poll-interval` | 1s |
| `batch-size` | 400 |
| `employee-cap` | 100 |
| `publisher-threads` | 2 |
| `tick-budget` | 3s |

The same machine, image rebuilt from that poller, `VUS=8`, `WARMUP=10s`, `DURATION=60s`. Each scenario was reseeded with `python3 load/generate.py` first, so a peak is that scenario's own rows. `./load/run.sh` used `DRAIN_TIMEOUT=120`. The kill command was the same as above. `outbox.pending` and `outbox.oldest.pending.seconds` are sampled every 10s, so the peak is the highest of those samples.

| Scenario | Before peak pending | Before oldest | Before drain | After peak pending | After oldest | After drain |
| --- | --- | --- | --- | --- | --- | --- |
| Ingest | 234,256 | 395s | stalled at 21.9s | 1,316 | 0s | 0.344s |
| Contention | 85,672 | 196s | still 85,672 after 120s | 1,125 | 0s | 0.334s |
| Mixed | 133,516 | 292s | stalled at 21.6s | 476 | 0s | 0.336s |
| Kill under load | 25,665 after 120s | 571s | 529s to pending 0 | 16,276 | 75s | 0.329s, pending 0 |

Before mixed and ingest include the unpublished rows of the earlier scenarios. Before contention followed the reads run, which had left nothing pending. After, every outbox row was `PUBLISHED` (`pending` 0, `failed` 0, `notPublished` 0). The age gauge has one-second resolution. It stores `extract(epoch from (clock_timestamp() - created_at))` in an `AtomicLong`, so a pending row younger than one second reads 0. Every sample during ingest, contention, and mixed was 0 while pending was above 0, so those rows were under a second old at the sample. The kill's 75s is the age while the process was down. At 21:17:33 that scrape still showed 16,276 pending; the database check at 21:17:36 found 0.

Ingest throughput fell. Steady window, error rate 0 both times: 19.07 req/s (1,907 events/s, p50 418.7 ms, p95 454.5 ms, p99 604.4 ms) before, and 15.87 req/s (1,587 events/s, p50 487.1 ms, p95 658.0 ms, p99 1,204.6 ms) after. 952 steady requests in 60s. Applied 84,920 of 112,800 sent. Docker CPU peaked at 391% for the app and 164% for Postgres (before: 102% and 254%). Hikari max stayed 10, active connections hit 10, and none were waiting. Contention steady rate was 11.23 req/s (674 requests, p50 675.4 ms) against 14.27 req/s before. Mixed warm reads were 6,029 req/s, p50 0.403 ms, against 8,124 req/s and 0.357 ms before.

On the ingest minute, `pg_stat_statements` showed the head lookup at mean 1.81 ms (232 calls), the per-employee run select at mean 0.44 ms (3,184 calls), and the `id = ANY` update at mean 0.30 ms. Claim size for that minute was 84,920 rows over 224 claims (mean 379, max 401). The highest `outbox.publish.rate` sample was 4,046 rows/s. That gauge is the last tick's rows divided by the tick's wall time, so an empty tick records 0.

Saved Prometheus scrapes for these runs were 33–46 KB and included `outbox_published_total`, `outbox_claim_size_rows`, `outbox_publish_rate_rows`, Hikari, and JVM memory. None was `# EOF`.

A rerun of that kill command after the invariant change exited 0. Of 110,600 events started, 41,500 came back classified (31,445 applied, 3,830 stale, 4,150 duplicate, 2,075 rejected) and 69,100 had no classifiable response (`events_unknown` 68,300, plus 800 from iterations k6 cut off before that counter moved). The database added 31,735 `processed_event` rows and 31,735 outbox rows, 290 more applies than the client was told about. Version regressions were 0. Cross-tenant rows were 0. The drain check found every outbox row `PUBLISHED` in 0.473s.

### Kafka publisher

`load/docker-compose.yml` starts a single-node KRaft broker, creates `hr-sync.employee-changes`, and sets the app to `hr-sync.publisher=kafka` with bootstrap `kafka:19092`. Host port 9094 is advertised as `localhost:9094`. The same machine and the same `VUS=8`, `WARMUP=10s`, `DURATION=60s` ingest minute, reseeded with `python3 load/generate.py`, measured on 2026-10-31. Error rate was 0. The before column is the logging publisher after the per-employee claim.

| | Logging publisher | Kafka publisher |
| --- | --- | --- |
| Steady req/s | 15.87 | 19.07 |
| Events/s | 1,587 | 1,907 |
| p50 | 487.1 ms | 419.8 ms |
| p95 | 658.0 ms | 468.9 ms |
| p99 | 1,204.6 ms | 1,063.7 ms |
| Peak `outbox_pending` | 1,316 | 87,118 |
| Highest `outbox.publish.rate` | 4,046 rows/s | 337.189 rows/s |
| Drain to pending 0 | 0.344s | 330.3s |

The Kafka minute classified 132,800 events: 99,920 applied, 12,960 stale, 13,280 duplicate, 6,640 rejected. The steady window was 1,144 requests. Peak pending is the highest 10s actuator sample. The publish-rate gauge is the last tick's rows divided by that tick's wall time. By the last scrape of the minute the broker had acknowledged 12,802 messages, and the fastest scrape interval was 304 messages/s. `./load/run.sh ingest` used a 90s drain timeout and returned at 122.0s with 54,317 still pending and 0 failed. A follow-up count reached 0 at 330.3s from the start of that drain, and every outbox row was `PUBLISHED`. The demo consumer on `localhost:9094` printed `read=99920 unique=99920 duplicates=0 orderViolations=0 malformed=0`.

Two publisher threads each wait for `acks=all` before the next event for that employee. The poller therefore moved a few hundred rows/s, and ingest kept the rest of the pool. The steady rate matches the earlier minute whose poller was stalled (19.07 req/s). The backlog is the cost of waiting for the broker. Ingest still returned 200.

`load/run.sh` now waits up to 600s for the drain. The logging rows above were taken with `DRAIN_TIMEOUT=120`.

### Kafka killed during ingest

```bash
DRAIN_TIMEOUT=720 ./load/kafka-down-under-load.sh
```

The script waits 25s, runs `docker kill hrsync-load-kafka`, lets k6 finish, prints the outbox, starts the broker, and checks invariants. The app process stayed up.

Steady window, with the broker down for most of the minute: 19.38 req/s (1,938 events/s, p50 413.1 ms, p95 445.5 ms, p99 1,077.7 ms), 1,163 steady requests, error rate 0. `events_http_fail` was 0. 136,300 events were classified: 102,545 applied, 13,310 stale, 13,630 duplicate, 6,815 rejected. The database added 102,545 `processed_event` rows and 102,545 outbox rows. Version mismatches were 0. Version regressions were 0. Cross-tenant rows were 0. Every load employee still had a processed row.

While the broker was down the outbox was 96,291 `PENDING`, 4 `FAILED`, and `PUBLISHED` had stopped at 306,730. 6,250 of this run's applies had been acknowledged before the kill. The four `FAILED` rows are `KafkaPublishException` at attempt 5. After the broker was healthy, pending fell from 96,291 to 0. The oldest-pending age went from 89.2s at 95,891 pending to 399.6s at 233 pending, and the next sample was pending 0, about 310s. The checker then sat until its 722.0s timeout because those four rows stayed `FAILED`, and the only failed check was `outbox did not end PUBLISHED`.

The poller blocks an employee when an earlier `FAILED` row is an `EMPLOYEE_DEACTIVATED`. These four are updates, so the following events for those employees published once the broker was back. The topic after both Kafka minutes printed `read=202461 unique=202461 duplicates=0 orderViolations=0 malformed=0`: the first minute's 99,920 messages plus 102,541 delivered applies from this one. The four exhausted event ids are absent. Replaying them would append the earlier sequence after those later events.

### Round publisher

The image was rebuilt with one publisher thread, `linger.ms` 1, `batch.size` 65536, and lz4. A claimed batch sends one row per employee, waits for that round's acks, then sends the next row. The same machine and the same `VUS=8`, `WARMUP=10s`, `DURATION=60s`. The database was not reseeded. The before column is the sequential-ack Kafka minute above. `./load/run.sh ingest` and `./load/kafka-down-under-load.sh` wait up to 360s and print pending every 2s.

| | Sequential acks | Rounds |
| --- | --- | --- |
| Steady req/s | 19.07 | 19.20 |
| Events/s | 1,907 | 1,920 |
| p50 | 419.8 ms | 412.3 ms |
| p95 | 468.9 ms | 508.1 ms |
| p99 | 1,063.7 ms | 1,152.6 ms |
| Peak `outbox_pending` | 87,118 | 1,680 |
| Highest `outbox.publish.rate` | 337.189 rows/s | 8,138.873 rows/s |
| Drain to pending 0 | 330.3s | 0.429s |

The round minute classified 132,800 events: 99,920 applied, 12,960 stale, 13,280 duplicate, 6,640 rejected. The steady window was 1,152 requests. Error rate was 0. Peak pending is the highest 10s actuator sample. Failed rows stayed 0, and every outbox row was `PUBLISHED`. The backlog stayed in the low thousands, and the post-run drain was under a second. Ingest throughput did not move: the earlier limit was the database, and the publish path is no longer the one filling the queue.

Killing the broker during a second minute, same parameters: 20.00 req/s (2,000 events/s, p50 400.6 ms, p95 437.1 ms, p99 1,144.6 ms), 1,200 steady requests, error rate 0. `events_http_fail` was 0. 139,300 events were classified: 104,635 applied, 13,770 stale, 13,930 duplicate, 6,965 rejected. The database added 104,635 `processed_event` rows and 104,635 outbox rows. Version mismatches were 0. Version regressions were 0. Cross-tenant rows were 0. While the broker was down the outbox was 69,035 `PENDING`, 0 `FAILED`, and `PUBLISHED` was 538,545 (35,600 of this run had been acknowledged before the kill). After the broker was healthy, pending fell from 69,035 to 0 in 20.855s and failed stayed 0. That outage backlog is not in the low thousands, because nothing is published while the broker is down. It is the sustained drain, about 3,310 rows/s over those 21s, against about 310s for 96,291 rows before.

Before this pair of minutes, the four `FAILED` updates from the sequential kill (ids 303196, 303201, 304336, 304344) were replayed so they would not block those employees. They were published after later events for the same employees were already on the topic, and their payloads have no `sourceVersion`. The demo consumer on `localhost:9094` then printed `read=407020 unique=407020 duplicates=0 orderViolations=4 malformed=0 stale=0`. The four order violations are those replayed ids. The two new minutes added 204,555 messages and no duplicates.

## Verification

`mvn -B test` on 2026-10-31: 210 tests, 0 failures. That count includes `ReconciliationBenchmarkTest`, which printed `reconciliation benchmark rows=200000 elapsedMs=2716 peakHeapBytes=87456424 maxHeapBytes=268435456`.

## Known limitations

`GET /employees/{id}` returns status, office, and shift. It does not return name, email, phone, pickup point, or either version. Those columns are in Postgres.

Logs may include personal field names (`name`, `email`, `phone`, `pickupPoint`, and the other names in `changedFields`). They do not include the values.

A mixed diff of about 5% of 200,000 rows takes about 36 s (36.217 s on this machine) because each create, update, and deactivation is its own transaction. The hash diff itself, on an identical 200,000-row export, takes about 2 s (1.814 s and 2.217 s).
