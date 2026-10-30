# Operations

Prometheus text is `GET /actuator/prometheus`. Only an operator can call it. Send `Accept: text/plain`. Gauges are sampled every 10 seconds (`hr-sync.metrics.sample-interval`) from indexed queries. A scrape reads the last sample. A failed sample leaves the previous gauge values in place.

Timers publish histogram buckets and client-side p50, p95, and p99 gauges. Prefer `histogram_quantile` over the `_bucket` series when you add several instances together.

## Metrics

| Series | Type | Tags | Meaning |
| --- | --- | --- | --- |
| `ingest_applied_total` | counter | `reason=none`, `channel` | Ingest applied |
| `ingest_stale_ignored_total` | counter | `reason`, `channel` | Older version ignored |
| `ingest_duplicate_total` | counter | `reason`, `channel` | Already processed version |
| `ingest_rejected_total` | counter | `reason`, `channel` | Validation or apply rejection |
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
| `outbox_pending` | Above 5,000 for 2 minutes. The 10s gauge peaked at 1,316 during ingest, 1,125 during contention, and 476 during mixed |
| `outbox_oldest_pending_seconds` | Above 30. Healthy samples stayed at 0. The stalled claim reached 196s in a 60s contention run. A killed process reached 75s while it was down, then the queue cleared |
| `outbox_publish_rate_rows` | 0 for 1 minute while `outbox_pending` is above 0. Idle is pending 0, which is not this alert. The highest ingest sample was 4,046 rows/s |
| `outbox_failed` | Above 0. Every load sample was 0 |
| `reconciliation_halted_total` | Any increase |
| HTTP 5xx | More than 1% of `http_server_requests_seconds_count` for 5 minutes |
| `auth_login_failures_total` | More than 20 per minute |

## 3 AM runbook

1. Sign in as the operator and open `GET /actuator/prometheus`. Read `outbox_pending`, `outbox_oldest_pending_seconds`, `outbox_publish_rate_rows`, `outbox_claim_size_rows`, `outbox_failed`, `reconciliation_halted_total`, and the 5xx rate.
2. Take the `correlationId` from the error body or the `X-Correlation-Id` response header and find the same field in the JSON logs.
3. Failed outbox rows: `GET /outbox/failed?tenantId={tenant}` as the operator.
4. Replay one row: `POST /outbox/{id}/replay?tenantId={tenant}`.
5. Discard one row when it must not be published: `POST /outbox/{id}/discard?tenantId={tenant}` with `{"reason":"..."}`. The reason is required. Only a failed row can be changed.
6. A halted reconciliation: `GET /reconciliation/runs/{id}?tenantId={tenant}`. If the missing-row brake is accepted, `POST /reconciliation/runs/{id}/approve?tenantId={tenant}` as the operator.
7. A `503` with `Retry-After` means the database or the pool did not answer inside the timeout (about 3 seconds). Check Postgres. After it answers again, repeat the call. The body stays `{"code":"UNAVAILABLE","message":"Database is unavailable","correlationId":"..."}`.
