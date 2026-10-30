# hr-sync

HR roster sync: idempotent, out-of-order, self-healing employee records.

## Reconciliation benchmark

An identical 200,000-row CSV export was diffed against a 200,000-row store. Seeding the store is not included in the time. The benchmark JVM was started with `-Xmx256m` (`Runtime.maxMemory` was 268,435,456 bytes). Peak heap is the highest `MemoryMXBean` heap used, sampled every 5 ms during the diff, after a garbage collection that followed the seed. A heap pool's own peak is used when it is higher than those samples.

| Result | Value |
| --- | --- |
| Rows | 200,000 |
| Elapsed | 1.814 s (1,814 ms) |
| Peak heap | 136,320,024 bytes (130 MiB) |
| Heap cap | 256 MiB |

Machine: WSL2 (`Linux romrom 6.6.87.2-microsoft-standard-WSL2`), AMD Ryzen 7 7840HS (8 cores, 16 threads), 7,760,064 kB RAM, Docker Desktop 29.4.2, Temurin 21.0.13+9, PostgreSQL 16.15 (`postgres:16-alpine` via Testcontainers). Measured on 2026-10-30.

The benchmark is `ReconciliationBenchmarkTest`, excluded from the default Surefire run and executed separately with `mvn surefire:test@reconciliation-benchmark`.
