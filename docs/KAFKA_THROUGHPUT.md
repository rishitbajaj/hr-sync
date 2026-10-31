# Kafka publish throughput

Measured on 2026-10-31 on the load stack (`hrsync-load`, ports 5442, 8092, and 9094) with the Kafka publisher as it shipped: two publisher threads, `employee-cap` 100, batch size 400, `acks=all`, `linger.ms` 5, lz4. The ingest minute is the one in the README (8 virtual users, 10s warm-up, 60s hold). Prometheus scrapes and `pg_stat_statements` are from `load/data/ingest-observe`. `pg_stat_statements_reset()` ran at the start of that minute, and the statement snapshot was taken before the post-run drain.

## What the minute did

k6 addresses `a-{VU}-{index % 4}` and alternates `load-a` and `load-b`, so the hot set is 8 virtual users × 4 employees × 2 tenants. Each of those employees already had a deep pending run: the cap of 100, not the number of pending rows, bounds a claim.

`pg_stat_statements` for that minute:

| Query | Calls | Total | Mean |
| --- | --- | --- | --- |
| Head lookup | 36 | 18,039.4 ms | 501.10 ms |
| Per-employee `FOR UPDATE` run | 200 | 110.9 ms | 0.55 ms |
| `id = ANY` mark published | 200 | 136.8 ms | 0.68 ms |

200 runs across 36 claims is 5.6 distinct employees per claimed batch. The last five claims, between the scrapes at `1793420313` and `1793420322`, recorded 2,001 rows (mean 400.2). A 400-row claim at the cap of 100 is four employees; the minute-wide 5.6 is higher because earlier runs were shorter than the cap.

## Ack latency

`kafka_publish_seconds` is the time inside `KafkaEventPublisher.publish`, which blocks on `future.get` for that one record. Over those same two scrapes the histogram moved by 1,969 sends and 11.473s.

| | |
| --- | --- |
| Mean | 5.83 ms |
| p50 | 6.04 ms |
| p95 | 6.92 ms |
| p99 | 7.19 ms |

1,423 of the 1,969 sends landed in the bucket above 5.59 ms and at or under 6.99 ms. 524 finished at or under 5.59 ms. `linger.ms` is 5. The caller sends one record and waits, so the producer has nothing else to batch and holds that singleton until linger expires, then pays the broker round trip. The ack time sits on top of that 5 ms.

## Time waiting on acks

The 11.473s is thread time inside `publish`, on the two `outbox-publish` threads. Split across the threads that is 5.74s each during a 9s wall interval, so each publisher thread spent about 64% of that interval blocked on an ack. The rest of the interval is the head lookup and the gap between ticks (`fixedDelay` of 1s after a tick whose budget is 3s).

## Why the gauge peaks near 337 rows/s

`outbox.publish.rate` peaked at 337.189 rows/s. A thread publishes one employee's run in order and does not send the next record until the current ack returns, and a wave runs only `publisher-threads` employees (2) at once. In flight is therefore 2.

2 / 6.04 ms = 331 sends/s. 2 / 5.93 ms = 337 sends/s. The peak tick is that ceiling: two acks in flight, each about 6 ms, with the tick's other work too small to show. `max.in.flight.requests.per.connection` is 5 and does not raise the ceiling, because the next `send` is not called until `future.get` returns.

The head lookup is a second cost, not the ceiling. Its mean of 501 ms on a claim of 400 adds about 0.5s to a claim whose acks already take 400/2 × 6.04 ms = 1.21s, which is about 234 rows/s for that claim. The last 9s of scrapes acknowledged 1,969 messages, 219 rows/s of wall time, between the 331-row ack ceiling and the lookup-loaded claim.

Ingest in the same minute applied about 1,900 events/s. The publisher falls behind because a claimed batch of hundreds of rows is still delivered as two synchronous acks.

## What the round publisher changes

A claimed batch is split across the employees in it. Round k sends each employee's k-th row and waits for that round's acks before round k+1. A failed row drops that employee from later rounds. Rows are marked `PUBLISHED` only after their acks, in one update per tenant.

The row budget is spread across the heads in the claim, so a deep queue does not fill the batch with one employee. On the ingest shape above (about 64 hot employees, batch 400) that is about six rows per employee per claim and one ack wait per round for the whole set.

Producer settings after that measurement: `linger.ms` 1, `batch.size` 65536, lz4. A round is queued before anyone waits, so linger no longer holds a one-record batch for 5 ms, and 64 KiB holds the round in one produce request. `publisher-threads` is 1: the round already fans out, and a second thread would hold a second pool connection for the same wait. The Kafka tests still run with two threads. A 40-employee, 10-version drain on Testcontainers, including the applies, finished in 2.6 s, and the first claim published one row each from at least 10 employees.
