# Outbox claim under contention

Measured on 2026-10-30 against the load stack (`hrsync-load`, ports 5442 and 8092), before any poller change. The app was the image already running, with `statement_timeout` 3s, poll interval 1s, and batch size 100. The claim turns hash join and merge join off, then takes `LIMIT 100` with the per-employee `NOT EXISTS` guard.

## Run

Reseed, then the contention scenario: 8 virtual users, 10s warm-up, 80s hold, batches of 100 updates on `c-0`..`c-3` in tenants `load-a` and `load-b`.

```bash
python3 load/generate.py
docker run --rm --network hrsync-load --env-file load/.env \
  -e SCENARIO=contention -e BASE_URL=http://app:8080 -e VUS=8 -e WARMUP=10s -e DURATION=80s \
  -v "$PWD:/work" -w /work grafana/k6 run load/k6.js
```

k6: 127,200 events sent, 119,420 applied, 7,780 stale, 0 duplicate, 0 rejected, 0 HTTP failures. Steady p50 488.9 ms.

## What was left

| status | rows |
| --- | --- |
| PENDING | 119,330 |
| PUBLISHED | 200,650 |
| FAILED | 0 |

The 200,650 published rows are the 200,560 seed rows plus 90 rows published during this run. Every pending row is an `UPDATE`. Eight employees, and no other employee has a pending row:

| tenant | employee | pending |
| --- | --- | --- |
| load-b | c-1 | 14,999 |
| load-b | c-3 | 14,968 |
| load-a | c-0 | 14,953 |
| load-a | c-2 | 14,915 |
| load-a | c-3 | 14,897 |
| load-b | c-2 | 14,888 |
| load-a | c-1 | 14,866 |
| load-b | c-0 | 14,844 |

No `FAILED` row, and no `FAILED` `EMPLOYEE_DEACTIVATED` row, so nothing is sitting in front of a head and blocking that employee. The same `NOT EXISTS` predicate, run as a count, returns 8 claimable heads (1.157s with the planner free to hash-join). A poll can publish at most one row per employee, because every later pending row of that employee has an earlier pending row.

## Publish rate

`outbox.published` is a counter, so it moves when a row is published. `outbox.pending` is sampled every 10s, so its plateaus are the sample interval. Readings from `GET /actuator/metrics`:

| t (s) | pending gauge | published counter |
| --- | --- | --- |
| 0 | 0 | 33,965 |
| 5 | 0 | 33,991 |
| 10 | 4,629 | 34,015 |
| 20 | 4,629 | 34,047 |
| 25 | 13,815 | 34,055 |
| 30 | 13,815 | 34,055 |
| 87 | 102,046 | 34,055 |
| 112 | 119,330 | 34,055 |

The counter was already 33,965 from the previous process lifetime. This run added 90, all of them before t=25s. From t=25s to t=112s the counter did not move while the backlog went from about 14,000 to 119,330.

`pg_stat_statements` for the claim, reset at the start of the run: 13 calls, 90 rows, mean 629.5 ms. That is 6.9 rows per call that finished, never a batch of 100, and it matches the 90 published rows. After that, the app log has `QueryTimeoutException` on the claim (`scheduling-1`, about every 4s, 23 in the window that was read). A cancelled poll rolls the transaction back, so those calls publish nothing.

## EXPLAIN

Backlog at explain time: 119,330 pending. The app was paused so the poller was not also running the claim.

With hash join and merge join off, the plan for `LIMIT 100` is a nested-loop anti-join. Estimated cost is about 73.5 million. The outer side is `idx_outbox_pending_id`. The inner side materializes the pending rows (about 111,000) and, for the failed-deactivation arm, `idx_outbox_failed_deactivation`.

`EXPLAIN (ANALYZE)` of that statement did not finish. The session `statement_timeout` of 180s cancelled it at 178.8s, before a plan with actual times was returned.

The same statement with `statement_timeout` 3s, which is what the app sets, is cancelled at 3,003 ms: `canceling statement due to statement timeout`. No row is returned.

`LIMIT 8` under the same join settings does finish, because 8 is exactly the number of claimable heads. Actual time 2,313 ms, 8 rows. The inner probe is an index scan on `outbox_pkey` with `id < o.id`, and each of 100 outer rows removed about 200,646 rows by the tenant and status filter. Two seconds for eight rows is already against the 3s timeout. Asking for 100 rows when only 8 exist forces the scan to continue through the rest of the pending index, and that scan does not finish inside 3s or inside 180s.

## Root cause

The backlog is stalled, not slowly draining. After the first 90 rows, nothing more was published, and there is no failed row to blame.

The ordering guard is doing what it says: one claimable row per employee. Contention has eight employees, so each poll has at most eight rows it is allowed to publish. The claim still asks for 100. Postgres cannot stop after the eight heads, because the limit is not filled, and with hash and merge joins disabled the remaining search is a nested loop over the whole pending set. That exceeds the 3s statement timeout, the poll transaction rolls back, and the eight heads stay pending. The next poll does the same. The queue never shrinks.

A poll that only asked for the eight heads would still be marginal (2.3s at this size) and would publish at most eight rows per tick. The timeout is why the rate is zero rather than eight rows a second.
