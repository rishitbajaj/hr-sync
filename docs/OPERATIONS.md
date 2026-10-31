# Operations

Prometheus text is `GET /actuator/prometheus`. Only an operator can call it. Send `Accept: text/plain`. Gauges are sampled every 10 seconds (`hr-sync.metrics.sample-interval`) from indexed queries. A scrape reads the last sample. A failed sample leaves the previous gauge values in place.

Timers publish histogram buckets and client-side p50, p95, and p99 gauges. Prefer `histogram_quantile` over the `_bucket` series when you add several instances together.

## Metrics

| Series | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `ingest_applied_total` | counter | `reason=none`, `channel` | Ingest applied |
| `ingest_stale_ignored_total` | counter | `reason`, `channel` | Older version ignored |
| `ingest_duplicate_total` | counter | `reason`, `channel` | Already processed version |
| `ingest_rejected_total` | counter | `reason`, `channel` | Validation or data-specific apply rejection |
| `ingest_retryable_total` | counter | `reason`, `channel` | `DATABASE_UNAVAILABLE` or `NOT_PROCESSED`. The caller should resend |
| `ingest_duration_seconds` | histogram | | Ingest batch time |
| `reconciliation_duration_seconds` | histogram | | Reconciliation run or approve time |
| `reconciliation_runs_total` | counter | `status` | Runs by final status |
| `reconciliation_halted_total` | counter | `reason=MISSING_RATIO_EXCEEDED` | Runs stopped by the missing-row brake |
| `outbox_published_total` | counter | | Outbox rows published |
| `outbox_retries_total` | counter | | Outbox publish attempts that failed |
| `outbox_publish_rate_rows` | gauge | | Rows per second in the last poller tick. An empty tick records 0 |
| `outbox_claim_size_rows` | summary | | Rows claimed in one poll (`_count`, `_sum`, `_max`) |
| `outbox_pending` | gauge | | Pending outbox rows |
| `outbox_oldest_pending_seconds` | gauge | | Age of the oldest pending row. Zero when nothing is pending |
| `outbox_failed` | gauge | | Failed outbox rows |
| `kafka_publish_seconds` | histogram | | Time from send until the broker ack. Failures are not recorded here |
| `kafka_published_total` | counter | | Messages the broker acknowledged |
| `kafka_publish_failures_total` | counter | | Sends that failed before an ack |
| `kafka_publish_transient_total` | counter | | Broker, timeout, network, or replica failures. The row stays pending and its attempt count does not move |
| `kafka_publish_permanent_total` | counter | | Record-too-large, serialization, or authorization failures. These consume attempts and can become `FAILED` |
| `kafka_circuit_state` | gauge | | `0` closed, `1` half-open (one probe row), `2` open |
| `kafka_circuit_open_seconds` | gauge | | How long the current outage has kept the circuit open. Zero while closed |
| `kafka_circuit_unavailable_seconds_total` | counter | | Seconds the circuit has spent open |
| `deactivation_bookings_cancelled_total` | counter | | Bookings cancelled by a deactivation |
| `deactivation_seats_freed_total` | counter | | Seats freed by a deactivation |
| `deactivation_effect_failures_total` | counter | | Deactivation effects that failed |
| `auth_login_failures_total` | counter | | Login failures. No username |
| `auth_lockouts_total` | counter | | New lockouts |
| `auth_rejected_tokens_total` | counter | | Rejected bearer tokens |
| `cache_gets_total` | counter | `cache` (`offices` or `employees`), `result` (`hit` or `miss`) | Read-cache lookups |
| `http_server_requests_seconds` | timer | `status`, `uri`, `method` | HTTP calls, from Spring Boot |

Tags never include an employee id, a tenant id, a name, an email, a phone, a pickup point, a token, or a password. `channel` is `HR` or `TRANSPORT`. `reason` is a reason code.

## Gauge plans

Captured on a small table, with and without `set local enable_seqscan = off`. Both plans were index-only scans.

Pending depth (`select count(*) from outbox where status = 'PENDING'`):

```text
Aggregate (cost=8.15..8.16 rows=1 width=8)
  -> Index Only Scan using idx_outbox_pending_aggregate on outbox (cost=0.14..8.15 rows=1 width=0)
```

Oldest pending row:

```text
Result (cost=8.16..8.17 rows=1 width=32)
  InitPlan 1 (returns $0)
    -> Limit (cost=0.14..8.16 rows=1 width=40)
      -> Index Only Scan using idx_outbox_pending_created on outbox (cost=0.14..8.16 rows=1 width=40)
```

## Suggested alerts

Outbox thresholds are from the 2026-10-30 load on this machine (`VUS=8`, 60s, reseeded). The other rows were not remeasured.

| Signal | Suggested threshold |
| --- | --- |
| `outbox_pending` | Above 5,000 for 2 minutes. The 10s gauge peaked at 1,316 during the logging ingest. With the round publisher and Kafka up, on a fresh database, the highest ingest sample was 1,185. A broker killed under that publisher left 67,093 pending and drained them in 16.9s with `outbox_failed` still 0 |
| `outbox_oldest_pending_seconds` | Above 30. Healthy samples stayed at 0. The stalled claim reached 196s in a 60s contention run. A killed process reached 75s while it was down, then the queue cleared |
| `outbox_publish_rate_rows` | 0 for 1 minute while `outbox_pending` is above 0. Idle is pending 0, which is not this alert. The highest ingest sample was 4,046 rows/s |
| `outbox_failed` | Above 0. Every load sample was 0. Do not use this for a Kafka outage: a broker failure leaves the row `PENDING` and does not increment attempts |
| `kafka_circuit_open_seconds` | Above 2 minutes. The broker is not accepting publishes, ingest still returns 200, and the backlog stays pending. A short open during one blip is not this alert |
| `reconciliation_halted_total` | Any increase |
| `ingest_retryable_total` | Any increase for 1 minute. One batch during a database blip is expected and should stop once the caller resends. A continued increase means Postgres or the pool is still not answering |
| HTTP 5xx | More than 1% of `http_server_requests_seconds_count` for 5 minutes |
| `auth_login_failures_total` | More than 20 per minute |

## 3 AM runbook

1. Sign in as the operator and open `GET /actuator/prometheus`. Read `outbox_pending`, `outbox_oldest_pending_seconds`, `outbox_publish_rate_rows`, `outbox_claim_size_rows`, `outbox_failed`, `kafka_circuit_state`, `kafka_circuit_open_seconds`, `kafka_publish_transient_total`, `kafka_publish_permanent_total`, `kafka_published_total`, `reconciliation_halted_total`, and the 5xx rate.
2. Take the `correlationId` from the error body or the `X-Correlation-Id` response header and find the same field in the JSON logs.
3. Failed outbox rows: `GET /outbox/failed?tenantId={tenant}` as the operator.
4. Replay one row: `POST /outbox/{id}/replay?tenantId={tenant}`.
5. Discard one row when it must not be published: `POST /outbox/{id}/discard?tenantId={tenant}` with `{"reason":"..."}`. The reason is required. Only a failed row can be changed.
6. A halted reconciliation: `GET /reconciliation/runs/{id}?tenantId={tenant}`. If the missing-row brake is accepted, `POST /reconciliation/runs/{id}/approve?tenantId={tenant}` as the operator.
7. A `503` with `Retry-After` means the database or the pool did not answer inside the timeout (about 3 seconds). Check Postgres. A read returns `{"code":"UNAVAILABLE","message":"Database is unavailable","correlationId":"..."}`. An ingest returns `results` and `correlationId`: `RETRYABLE_ERROR` / `DATABASE_UNAVAILABLE` for the record that hit the database, and `RETRYABLE_ERROR` / `NOT_PROCESSED` for the ones the server did not attempt. After Postgres answers, send the batch again. `ingest_retryable_total` counts those outcomes.
8. Kafka outage: `kafka_circuit_state` is `2` and `kafka_circuit_open_seconds` is climbing, while `kafka_published_total` is flat and `outbox_pending` climbs. `outbox_failed` stays put. `POST /sync/events` still returns 200, and the new rows stay `PENDING` with the same attempt count. The poller waits out the backoff (up to 30 seconds, with jitter) and then probes a single row. Check the broker (`docker compose ps`, then the Kafka logs). Do not restart the app to clear the backlog, and do not ask the caller to resend. When a probe is acknowledged, the circuit closes and the poller drains each employee's rows in order. A row that was acknowledged and then crashed before `PUBLISHED` is delivered again with the same `eventId`. Consumers dedupe on `eventId`. A record that is too large, cannot be serialized, or is rejected for authorization still consumes attempts and can become `FAILED`; that is `kafka_publish_permanent_total`, not the circuit.
