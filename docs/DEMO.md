# Demo

Five to seven minutes on a fresh `docker compose up --build -d` with `SPRING_PROFILES_ACTIVE=demo` in `.env`. The commands are the README walkthrough. The bodies below are from one run with `HR_SYNC_BOOTSTRAP_ADMIN_TENANT=demo-co`. A run id, a correlation id, and a JWT change every time. `scripts/demo.sh` prints steps 1 to 8. Steps 9 and 10 are typed on their own so they can kill and pause this stack.

Set the shell variables once:

```bash
set -a; source .env; set +a
TENANT="$HR_SYNC_BOOTSTRAP_ADMIN_TENANT"
TOKEN=$(curl -sS -X POST http://localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"${HR_SYNC_BOOTSTRAP_ADMIN_USERNAME}\",\"password\":\"${HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
OP=$(curl -sS -X POST http://localhost:8080/auth/token -H 'Content-Type: application/json' \
  -d "{\"username\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME}\",\"password\":\"${HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD}\"}" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
```

## 1. Auth

The login body is `{"token":"<jwt>","expiresInSeconds":900}`.

## 2. A batch with a bad record

```bash
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "insert into office (id, tenant_id, code) values (gen_random_uuid(), '${TENANT}', 'BLR') on conflict (tenant_id, code) do nothing;"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-bad\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Bad\",\"email\":\"bad@example.com\",\"phone\":\"12345\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}},
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}
  ]}"
```

```text
INSERT 0 1
{"results":[{"tenantId":"demo-co","employeeId":"walk-bad","sourceVersion":1,"outcome":"REJECTED","reasonCode":"INVALID_PHONE"},{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":1,"outcome":"APPLIED"}]}
```

## 3. Replay

```bash
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Ada\",\"email\":\"ada@example.com\",\"phone\":\"+15550000001\",\"manager\":\"M1\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
```

```text
{"results":[{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":1,"outcome":"IGNORED","reasonCode":"DUPLICATE"}]}
```

## 4. Out-of-order versions

```bash
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":3,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Three\",\"shift\":\"NIGHT\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":2,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Two\",\"shift\":\"DAY\"}}]}"
curl -sS http://localhost:8080/employees/walk-1 -H "Authorization: Bearer $TOKEN"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, shift from employee where tenant_id = '${TENANT}' and employee_id = 'walk-1';"
```

```text
{"results":[{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":3,"outcome":"APPLIED"}]}
{"results":[{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":2,"outcome":"IGNORED","reasonCode":"STALE_IGNORED"}]}
{"tenantId":"demo-co","employeeId":"walk-1","status":"ACTIVE","officeCode":"BLR","shift":"NIGHT"}
   name    | hr_version | shift
-----------+------------+-------
 Ada Three |          3 | NIGHT
```

## 5. Field ownership

```bash
curl -sS -X POST http://localhost:8080/sync/transport-events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":1,\"type\":\"UPDATE\",\"fields\":{\"pickupPoint\":\"Gate 4\",\"specialAssistance\":\"wheelchair\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-1\",\"sourceVersion\":4,\"type\":\"UPDATE\",\"fields\":{\"name\":\"Ada Owned\",\"pickupPoint\":\"Gate 9\",\"specialAssistance\":\"none\"}}]}"
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "select name, hr_version, pickup_point, transport_version from employee where tenant_id = '${TENANT}' and employee_id = 'walk-1';"
```

```text
{"results":[{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":1,"outcome":"APPLIED"}]}
{"results":[{"tenantId":"demo-co","employeeId":"walk-1","sourceVersion":4,"outcome":"APPLIED"}]}
   name    | hr_version | pickup_point | transport_version
-----------+------------+--------------+-------------------
 Ada Owned |          4 | Gate 4       |                 1
```

## 6. Reconciliation brake

`python3 scripts/generate-exports.py` writes `scripts/out/export-200000.csv` (200,001 lines) and `scripts/out/export-120000.csv` (120,001 lines). The short file is the first 120,000 employees. Against a roster of all 200,000, the suite's cutoff test records `HALTED`, `missingCount` 80000, `activeCount` 200000, `missingRatio` 0.40, and `toDeactivate` 0.

This video uses a one-row file that omits `walk-1`, so the halt is visible without loading 200,000 rows:

```bash
printf '%s\n' 'tenantId,employeeId,sourceVersion,name,email,phone,manager,officeCode,shift' \
  "$TENANT,walk-new,1,New,new@example.com,+15550000002,M1,BLR,DAY" > /tmp/truncated.csv
curl -sS -X POST "http://localhost:8080/reconciliation/runs?tenantId=$TENANT" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: text/csv' \
  --data-binary @/tmp/truncated.csv
```

```text
{"id":"77e6e462-ae7e-4754-951d-6c67795a759b","tenantId":"demo-co","status":"HALTED","unchanged":0,"toCreate":1,"toUpdate":0,"toDeactivate":0,"missingCount":1,"activeCount":1,"exportRowCount":1,"missingRatio":1.0,"threshold":0.3,"haltReason":"MISSING_RATIO_EXCEEDED","rejected":0,"rejectedByReason":{},"rejections":[]}
```

`walk-1` is still active. `toDeactivate` stays 0 because the missing employee is saved and not applied.

## 7. Operator approval

Copy `id` into `RUN`. An admin token receives 403.

```bash
curl -sS -X POST "http://localhost:8080/reconciliation/runs/$RUN/approve?tenantId=$TENANT" \
  -H "Authorization: Bearer $OP"
curl -sS http://localhost:8080/employees/walk-1 -H "Authorization: Bearer $TOKEN"
```

The approval body keeps `status` `APPROVED` and `toDeactivate` 0. The read is:

```text
{"tenantId":"demo-co","employeeId":"walk-1","status":"DEACTIVATED","officeCode":"BLR","shift":"NIGHT"}
```

## 8. Deactivation cancels the booking

```bash
curl -sS -X POST http://localhost:8080/demo/trips -H 'Content-Type: application/json' \
  -d "{\"tenantId\":\"$TENANT\",\"tripId\":\"trip-walk\",\"capacity\":4}"
curl -sS -X POST http://localhost:8080/demo/bookings -H 'Content-Type: application/json' \
  -d "{\"bookingId\":\"book-walk\",\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"tripId\":\"trip-walk\",\"startsAt\":\"2026-12-01T09:00:00Z\"}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Rider\",\"email\":\"rider@example.com\",\"phone\":\"+15550000003\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}"
curl -sS -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-book\",\"sourceVersion\":2,\"type\":\"DEACTIVATE\"}]}"
curl -sS http://localhost:8080/demo/bookings
```

The booking is created with `"cancelled":false`. After the deactivate is published, the board is:

```text
{"trips":[{"tenantId":"demo-co","tripId":"trip-walk","capacity":4,"occupied":0}],"bookings":[{"bookingId":"book-walk","tenantId":"demo-co","employeeId":"walk-book","tripId":"trip-walk","startsAt":"2026-12-01T09:00:00Z","cancelled":true}]}
```

The poller interval is one second. On the earlier walkthrough the cancelled board was the next read. A later fresh clone of the same procedure returned `"cancelled":false` on the immediate `GET` and `"cancelled":true` two seconds later.

## 9. Kill mid-operation

A create returns in a few milliseconds, so this step holds a lock until the process is killed. The in-flight request gets no HTTP response. The row is absent after restart, and the same create applies once.

```bash
docker compose exec -T postgres psql -U hrsync -d hrsync \
  -c "BEGIN; LOCK TABLE employee IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(8); COMMIT;" &
sleep 0.3
curl -sS -m 15 -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d "{\"events\":[{\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-kill\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Kill\",\"email\":\"kill@example.com\",\"phone\":\"+15550000009\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}]}" &
sleep 0.6
docker compose kill app
```

curl prints `curl: (52) Empty reply from server`. Release the lock if it is still held, then start the app and wait for health:

```bash
docker compose exec -T postgres psql -U hrsync -d hrsync -c \
  "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE query LIKE '%pg_sleep%' AND pid <> pg_backend_pid();"
docker compose start app
curl -sS http://localhost:8080/health
```

`GET /health` returns `{"status":"UP"}`. The killed create did not commit:

```text
 employee_id | status | hr_version
-------------+--------+------------
(0 rows)
```

Post the same create. The token from step 1 is still valid.

```text
{"results":[{"tenantId":"demo-co","employeeId":"walk-kill","sourceVersion":1,"outcome":"APPLIED"}]}
```

```text
 employee_id | status | hr_version
-------------+--------+------------
 walk-kill   | ACTIVE |          1
```

`docker compose kill` is SIGKILL. `docker stop` is SIGTERM. The app stops claiming new outbox rows, finishes the in-flight round, and Tomcat finishes in-flight HTTP. `server.shutdown` is `graceful`, `spring.lifecycle.timeout-per-shutdown-phase` and `hr-sync.outbox.shutdown-timeout` are 20s, and Compose `stop_grace_period` is 30s.

On 2026-10-31 the load app (`load/docker-compose.yml`, port 8092) was stopped during ingest (`VUS=8`, `WARMUP=10s`, `DURATION=60s`). `docker stop hrsync-load-app` was issued 25s after k6 started and returned in about 5s. While the process was down the outbox was:

```text
  status   | count
-----------+--------
 PENDING   |  18120
 PUBLISHED | 420690
(2 rows)
```

There was no `FAILED` row. After `docker start`, `python3 load/invariants.py check sigterm --drain-timeout 360` printed:

```text
draining pending=16554 failed=0 oldestPendingSeconds=73.291
draining pending=4154 failed=0 oldestPendingSeconds=63.976
{
  "scenario": "sigterm",
  "outcomeSum": 39200,
  "classified": 39200,
  "attempted": 1554700,
  "httpFail": 0,
  "unknown": 1515500,
  "other": 0,
  "applied": 29720,
  "stale": 3600,
  "duplicate": 3920,
  "rejected": 1960,
  "processedDelta": 29720,
  "outboxDelta": 29720,
  "drainSeconds": 5.437,
  "oldestPendingSecondsAfter": 0.0,
  "pendingAfter": 0,
  "failedAfter": 0,
  "notPublishedAfter": 0,
  "versionMismatches": 0,
  "versionRegressions": 0,
  "employeesWithoutProcessed": 0,
  "crossTenantRows": 0,
  "readLeaks": 0,
  "coldExhausted": 0,
  "allowUnacked": false,
  "ok": false,
  "failures": [
    "events with an unknown outcome"
  ]
}
```

The 1,515,500 unknown events are the 15,155 requests that got HTTP status 0 after the process stopped accepting. Each batch is 100 events. The rows the client was told were applied match the database: `processedDelta` and `outboxDelta` are both 29,720. Pending fell from 16,554 to 0 in 5.437s and failed stayed 0. The same check with `--allow-unacked`, which is how the script treats a kill, printed `"ok": true` and `"failures": []`. That second check started from an empty queue, so its `drainSeconds` was 0.504.

## 10. Metrics

```bash
curl -sS -D - -o /tmp/hr-sync-metrics.txt \
  -H "Authorization: Bearer $OP" -H 'Accept: text/plain' \
  http://localhost:8080/actuator/prometheus | head -n 12
grep -E '^outbox_' /tmp/hr-sync-metrics.txt
```

On the walkthrough above, before the kill, the response was HTTP 200, `Content-Type: text/plain;version=0.0.4;charset=utf-8`, `Content-Length: 47020`, and the outbox lines were:

```text
outbox_claim_size_rows_count 2
outbox_claim_size_rows_sum 8.0
outbox_claim_size_rows_max 4.0
outbox_failed 0.0
outbox_oldest_pending_seconds 0.0
outbox_pending 0.0
outbox_publish_rate_rows 85.226
outbox_published_total 8.0
```

`outbox_published_total` counted the eight notices from the walkthrough. The publish-rate sample is the last tick, so that number moves.

## 11. Paused database

An employee read that has to reach Postgres returns 503. Pause the database, then read an id that is not in the cache:

```bash
docker compose pause postgres
curl -sS -D - http://localhost:8080/employees/walk-missing -H "Authorization: Bearer $TOKEN"
docker compose unpause postgres
```

The paused read took 3.138 seconds and returned:

```text
HTTP/1.1 503
Retry-After: 3
{"code":"UNAVAILABLE","message":"Database is unavailable","correlationId":"96a79949-3c8b-455e-b3ed-ee28ac032f87"}
```

After unpause, the same read took 0.022 seconds and returned 404:

```text
{"code":"NOT_FOUND","message":"Employee not found","correlationId":"6063db60-a478-4b90-90eb-7bf10e6fbe21"}
```

A batch sent while Postgres is paused is 503, not a rejected record. Pause the database, post three creates, then unpause and send the same body:

```bash
docker compose pause postgres
curl -sS -D - -o /tmp/hr-retry-body.txt -w 'time:%{time_total} code:%{http_code}\n' \
  -X POST http://localhost:8080/sync/events \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H 'X-Correlation-Id: probe-retry' \
  -d "{\"events\":[
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-retry-1\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"One\",\"email\":\"one@example.com\",\"phone\":\"+15550000011\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}},
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-retry-2\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Two\",\"email\":\"two@example.com\",\"phone\":\"+15550000012\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}},
    {\"tenantId\":\"$TENANT\",\"employeeId\":\"walk-retry-3\",\"sourceVersion\":1,\"type\":\"CREATE\",\"fields\":{\"name\":\"Three\",\"email\":\"three@example.com\",\"phone\":\"+15550000013\",\"officeCode\":\"BLR\",\"shift\":\"DAY\"}}
  ]}"
docker compose unpause postgres
```

On this run the paused post took 3.247 seconds. The status line was `HTTP/1.1 503`, `Retry-After: 3`, `X-Correlation-Id: probe-retry`, and the body was:

```text
{"results":[{"tenantId":"demo-co","employeeId":"walk-retry-1","sourceVersion":1,"outcome":"RETRYABLE_ERROR","reasonCode":"DATABASE_UNAVAILABLE"},{"tenantId":"demo-co","employeeId":"walk-retry-2","sourceVersion":1,"outcome":"RETRYABLE_ERROR","reasonCode":"NOT_PROCESSED"},{"tenantId":"demo-co","employeeId":"walk-retry-3","sourceVersion":1,"outcome":"RETRYABLE_ERROR","reasonCode":"NOT_PROCESSED"}],"correlationId":"probe-retry"}
```

No `walk-retry-*` row was stored. After unpause, the same body returned HTTP 200 in 0.055 seconds, every result `APPLIED`. Each employee then had `hr_version` 1, one `processed_event` row, and one outbox row.
