# Audit: Phases 1–5 and the review fixes

Read-only audit of local `main` at `e792710` (17 commits ahead of `origin/main` `93f9fd0`). Phase 6 was not started. Temporary mutations were reverted with `git checkout -- .`. The only files this audit leaves behind are this report and the README benchmark table.

The narrative in sections 1–8 is that original audit. The verdict table and the status section below are the state after branch `fix-audit-findings`. Phase 6 was still not started.

Verdicts are PASS, PARTIAL, or FAIL. A PASS cites a test, a file, or command output.

## Verdicts

| Item | Verdict |
| --- | --- |
| Fresh clone, `docker compose up --build`, Flyway V1–V8, `/health` UP | PASS |
| `mvn clean test` on the clone: 46 tests, 0 failures, 0 skipped | PASS |
| `.gitignore` covers build output, IDE files, secrets, and local `.env` | PASS |
| No credentials outside docker-compose / env configuration | PASS |
| Commits are small, and `test:` / `docs:` commits match their messages | PASS |
| Ingest create / update / deactivate with versions | PASS |
| Per-record validation (phone, email, office, required fields, shift) | PASS |
| One bad record does not fail the batch | PASS |
| Per-record error report with reason codes | PASS |
| Batch size and body size limits | PASS |
| Stale version ignored | PASS |
| Duplicate is a no-op | PASS |
| Idempotency key `(tenant, employee, channel, version)` | PASS |
| Replaying the same feed changes nothing | PASS |
| Out-of-order v7 before v6 | PASS |
| One batch of out-of-order events for one employee | PASS |
| Concurrency on one employee: highest version wins | PASS |
| First-create race | PASS |
| Update-before-create and deactivate-before-create | PASS |
| `PLACEHOLDER` excluded from active counts and reads | PASS |
| HR never overwrites `pickup_point` or `special_assistance` | PASS |
| Transport endpoint never writes HR fields | PASS |
| `docs/CONFLICT_RULES.md` matches the code | PASS |
| One outbox row per applied change, same transaction | PASS |
| No outbox row for stale, duplicate, or rejected | PASS |
| Rollback leaves no outbox row | PASS |
| Monotonic outbox ids | PASS |
| Outbox payload has no personal values | PASS |
| Poller uses `FOR UPDATE SKIP LOCKED` | PASS |
| Per-employee order across two pollers, including the `NOT EXISTS` guard | PASS |
| Retry, backoff, and `FAILED` | PASS |
| Placeholder creation emits no event | PASS |
| Crash-before-publish with a deduping consumer | PASS |
| Streaming CSV parse | PASS |
| Hash per record and hashed buckets | PASS |
| Only differing buckets are drilled into | PASS |
| Live event during reconciliation is not overwritten | PASS |
| Brake halts and deactivates nobody | PASS |
| Missing count visible on GET | PASS |
| Threshold 0.10 and 0.50 | PASS |
| Approve is operator-only and single-use | PASS |
| `PLACEHOLDER` excluded from the missing ratio | PASS |
| Benchmark exists | PASS |
| No phone, email, name, or pickup value in logs, API, errors, outbox, or metrics | PARTIAL |
| Mutation: stale check `<` changed to `<=` | FAIL (not caught) |
| Mutation: stale check removed entirely | PASS (caught) |
| Mutation: `processed_event` insert removed | PASS (caught) |
| Mutation: HR event writes `pickup_point` | PASS (caught) |
| Mutation: outbox insert skipped | PASS (caught) |
| Mutation: `NOT EXISTS` ordering guard removed | PASS (caught) |
| Mutation: per-record transaction boundary removed | PASS (caught) |
| Mutation: whole batch shares one transaction | PASS (caught) |
| Mutation: brake never halts | PASS (caught) |
| Mutation: approval check always passes | PASS (caught) |
| Mutation: deactivation version guard removed | PASS (caught) |
| 200k mixed ~5% diff under `-Xmx256m` | PASS |
| 400k identical diff under `-Xmx256m`, peak does not double | PASS |
| 200k identical diff under `-Xmx128m`, lowest passing heap | PASS at 48 MiB |
| Every table has `tenant_id` in its key or a unique constraint | PARTIAL |
| Every query filters by tenant | PARTIAL |
| Poller partial index is used by the claim query | PASS |
| Reconciliation scan index | PARTIAL |
| N+1 / per-row round trips on the apply path | PARTIAL |
| Retention for `processed_event` and `outbox` | PASS |
| Kill mid-reconciliation is resumable or safely re-runnable | PASS |
| Malformed CSV, wrong width, empty file | PASS |
| Duplicate employee ids in one export | PASS |
| File for the wrong tenant | PASS |
| Postgres down for 10 seconds during ingest and polling | PARTIAL |
| Two reconciliation runs for the same tenant | PASS |

## Status after the fixes

| Item | Status | Evidence |
| --- | --- | --- |
| `.env` ignored; no default datasource or operator password | PASS | `.gitignore` lists `.env`. `application.yml` uses empty defaults. `RequiredSecrets` fails startup with the variable name. `.env.example` and `docker-compose.yml` require `POSTGRES_PASSWORD` and `HR_SYNC_OPERATOR_PASSWORD`. `RequiredSecretsTest` |
| One running reconciliation per tenant, leftover run marked interrupted | PASS | `V9__one_running_reconciliation.sql`. `ReconciliationRunTest.aSecondRunForTheSameTenantIsRejectedWhileTheFirstIsRunning` (HTTP 409, one deactivation). `restartMarksAnInterruptedRunFailedAndANewRunWorks` (`haltReason=INTERRUPTED`, then a new run completes). A killed run is not resumed |
| Counts move only on `APPLIED`; tenant is required; duplicate employee ids rejected | PASS | `ReconciliationRunTest.aFileForAnUnknownTenantReportsZeroCreates` (`toCreate=0`, `rejected=1`, `UNKNOWN_OFFICE`). `aFileContainingAnotherTenantIsRejected` (HTTP 400). `duplicateEmployeeIdsInOneExportAreRejected` (HTTP 400, the count of duplicate ids, no names). Duplicate detection uses a temp table. A later full-suite run of the 256 MiB benchmark stayed green (2,317 ms, peak 83,089,216 bytes) |
| Poller claim uses the partial indexes | PASS | `V11__outbox_claim_index.sql` drops `idx_outbox_unpublished`, `idx_outbox_unpublished_id`, and `idx_outbox_pending`, and adds `idx_outbox_pending_id` and `idx_outbox_pending_aggregate`. Claim is `status = 'PENDING' AND next_attempt_at <= ?` plus the per-employee `NOT EXISTS`. `EXPLAIN (ANALYZE)` on Postgres 16.15, 105,000 pending rows, `LIMIT 20`: before, seq scan plus hash anti-join, 93.308 ms (the original audit sample was 116.357 ms). After, with hash and merge joins off for that transaction: nested loop, 0.264 ms with all 105,000 still pending, and 7.960 ms after the oldest 100,000 were marked published. Both are under 10 ms |
| Poller order, replay, and per-record transaction tests catch the mutations | PASS | Removing `NOT EXISTS` failed `OutboxPollerTest.aLaterVersionStaysUnclaimedWhileItsEarlierVersionIsInFlight` (`expected: 0 but was: 1`). Removing `insertProcessed` failed `OrderingScenariosTest.replayingAFeedLeavesTheDatabaseUnchanged` (`expected: DUPLICATE but was: STALE_IGNORED`). `STALE_IGNORED` remains the outcome for a version that was never applied and is not newer than the stored version. Removing `REQUIRES_NEW` failed `SyncEventsHttpTest.aFailedRecordInsideAnOuterTransactionDoesNotRollBackItsNeighbors` (`expected: APPLIED but was: REJECTED`). Each mutation was restored |
| Retention | PASS | `RetentionJob` deletes old `PUBLISHED` outbox rows and old `processed_event` rows, `hr-sync.retention.batch-size` at a time. `FAILED` and `PENDING` outbox rows stay. `RetentionJobTest`. The README states that the version comparison is the backstop after a `processed_event` row is pruned |
| `tenant_id` on deactivation, GET scoped by tenant | PASS for those two | `V12__deactivation_tenant.sql` primary key `(tenant_id, run_id, employee_id)`. `GET /reconciliation/runs/{id}?tenantId=` is required; another tenant is 404. `ReconciliationRunTest.aRunIsVisibleOnlyForItsTenant`. `outbox` and `import_run` still use a surrogate id, so "every table has tenant_id in its key" stays PARTIAL. The poller and approve still load by id. Auth is unchanged |
| Privacy and the 36 s mixed diff | PARTIAL, documented | README "Known limitations": field names may be logged, values are not. The 5% mixed diff is about 36 s because each change is its own transaction. The hash diff on an identical export is about 2 s (1.814 s and 2.217 s) |

The SQL `<` versus `<=` mutation is still not caught. Postgres-down handling, reconciliation seq scan, per-row apply cost, and the Phase 6 gaps in section 7 are unchanged.

## 1. Clean build and run

**PASS.** Local `main` was cloned to `/tmp/hr-sync-audit` (this is the local repository, not GitHub; `origin/main` is 17 commits behind). `docker compose -p hr-sync-audit up --build -d` from that directory started `hr-sync-audit-app-1` and `hr-sync-audit-postgres-1`. App log:

```
Migrating schema "public" to version "1 - init schema"
...
Migrating schema "public" to version "8 - import run deactivation"
Successfully applied 8 migrations to schema "public", now at version v8
Started HrSyncApplication in 8.841 seconds
```

`curl http://localhost:8080/health` returned `{"status":"UP"}`.

**PASS.** `mvn clean test` in `/tmp/hr-sync-audit` (Temurin 21, Maven): `BUILD SUCCESS`, Maven total time `01:43 min`. First Surefire execution: `Tests run: 45, Failures: 0, Errors: 0, Skipped: 0`. Second execution (`ReconciliationBenchmarkTest`): `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`, and printed `reconciliation benchmark rows=200000 elapsedMs=2217 peakHeapBytes=131999968 maxHeapBytes=268435456`. A search of `src` for `@Disabled` found no matches. No test was skipped.

**PARTIAL.** `.gitignore` ignores `target/`, `.idea/`, `*.iml`, `.classpath`, `.project`, `.settings/`, `.vscode/`, `*.log`, and `.DS_Store`. It does not ignore `.env`. No `.env` is committed (`git ls-files` has none; the file is absent). Fix: add `.env` to `.gitignore`.

**PARTIAL.** `docker-compose.yml` sets `POSTGRES_PASSWORD` to `hrsync`, which is the local compose password. The same default is also compiled into config: `src/main/resources/application.yml` uses `${SPRING_DATASOURCE_PASSWORD:hrsync}` and `${HR_SYNC_OPERATOR_PASSWORD:change-me}`. `ReconciliationSettings.operatorPassword` defaults to `"change-me"`. The test profile uses `test-operator`. Fix: require the operator password and the datasource password from the environment, with no default in source.

**PASS.** `git log --oneline` is 18 commits from `93f9fd0 chore: skeleton, schema, docker compose` through `e792710 test: benchmark a 200k roster diff under a 256m heap`. Each `test:` and `docs:` commit contains what the subject says (`git show --stat`):

| Commit | Files |
| --- | --- |
| `e792710` test: benchmark a 200k roster diff under a 256m heap | `ReconciliationBenchmarkTest.java`, `pom.xml`, `README.md` |
| `f65121a` test: per-employee outbox order across pollers | `OutboxPollerTest.java` only (+105) |
| `54de201` test: outbox failure injection | `OutboxPollerTest.java` only (+65) |
| `eaf3597` test: concurrent first create of one employee | `OrderingScenariosTest.java` only (+42) |
| `cdbe38d` docs: conflict rules | `docs/CONFLICT_RULES.md` only (+63) |
| `30d3cc1` test: concurrency and out-of-order scenarios | `OrderingScenariosTest.java` only (+297) |

`e792710` also wires Surefire and writes the measured README numbers. That is the benchmark.

## 2. Requirement traceability

### Ingest

| Requirement | Test |
| --- | --- |
| Create, update, and deactivate with versions | `ReconciliationRunTest.exportCreatesUpdatesAndDeactivatesThroughTheApplier`; `OrderingScenariosTest.oneBatchOfOutOfOrderEventsEndsAtTheHighestVersion` |
| Phone | `BatchIngestTest.batchOfTenAcceptsSevenAndRejectsThreeWithoutPersonalData` (`INVALID_PHONE` on `emp-02`) |
| Email | `BatchIngestTest.malformedEmailIsRejectedAndDoesNotAppearInLogs` (`INVALID_EMAIL`) |
| Office | same batch test (`UNKNOWN_OFFICE` on `emp-05`) |
| Required fields | same batch test (`MISSING_REQUIRED_FIELD` on `emp-09`) |
| Shift | `OrderingScenariosTest.malformedShiftIsRejected` (`INVALID_SHIFT`). Pattern is in `EventValidator`: `[A-Za-z0-9][A-Za-z0-9 -]{0,31}` |
| One bad record does not fail the batch | `BatchIngestTest.storeFailureDoesNotAbortTheBatchOrLogPersonalData`; `SyncEventsHttpTest.databaseFailureOnRecordFourDoesNotAbortTheRestOfTheBatch` (record 4 name of 250 characters is `APPLY_FAILED`, neighbors `APPLIED`, HTTP 200) |
| Per-record reason codes | the batch test asserts a reason on each rejected record. Response type is `SyncRecordResponse` |
| Batch size and body size | `IngestGuardTest.batchLargerThanTheCapIsRejectedBeforeAnyRecordIsApplied` (`BATCH_TOO_LARGE`), `bodyLargerThanTheCapIsRejected`, `chunkedBodyOverTheLimitIsRejectedWhileItIsRead` (`REQUEST_TOO_LARGE`) |

### Ordering and idempotency

| Requirement | Test |
| --- | --- |
| Stale ignored (`incoming <= stored`) | `EmployeeChangeApplierTest.equalOrLowerVersionIsStale`. SQL is `hr_version < ?` / `transport_version < ?` in `EmployeeChangeService` |
| Duplicate no-op | `EmployeeChangeApplierTest.duplicateIsANoOpEvenWhenTheVersionWouldAlsoBeStale`; `OutboxPollerTest.appliedChangeWritesOneOutboxRowAndNoOpsWriteNone` asserts reason `DUPLICATE` |
| Key `(tenant, employee, channel, version)` | unique index `uq_processed_event` in V2. `OutboxPollerTest` asserts the index text. `docs/CONFLICT_RULES.md` states the same key |
| Replay changes nothing | `OrderingScenariosTest.replayingAFeedLeavesTheDatabaseUnchanged`. `assertNoOp` accepts `DUPLICATE` or `STALE_IGNORED` |
| v7 before v6 | `OrderingScenariosTest.laterOfficeTransferWinsWhenAnEarlierChangeArrivesAfterIt` and `officeTransferBeforeAnEarlierShiftChangeKeepsTheVersion7Shift` |
| One batch, out of order, one employee | `OrderingScenariosTest.oneBatchOfOutOfOrderEventsEndsAtTheHighestVersion` |
| Concurrent versions, highest wins | `OrderingScenariosTest.concurrentVersionsKeepTheHighestAndDoNotEscape` |
| First-create race | `OrderingScenariosTest.twentyThreadsCreatingTheSameNewEmployeeDoNotFailOnTheUniqueKey` |
| Update-before-create and deactivate-before-create | `EmployeeChangeApplierTest.updateBeforeCreateRecordsATombstoneAtThatVersion`, `deactivateBeforeCreateStaysDeactivatedUntilANewerCreate`; `OrderingScenariosTest.updateOrDeactivateBeforeCreateRecordsAVersionTombstone` |
| `PLACEHOLDER` hidden | `EmployeeChangeApplierTest.transportFirstSightingStaysHiddenUntilAnHrCreate`; `EmployeeRepository.findVisibleByTenantIdAndEmployeeId` excludes `PLACEHOLDER`; active count uses `status = 'ACTIVE'`. `ReconciliationRunTest.identicalExportIsAnEmptyDiffAndChangesNothing` and `missingRatioThresholdIsConfigurable` keep a placeholder out of the count |

### Ownership

| Requirement | Test |
| --- | --- |
| HR does not write pickup or assistance | `EmployeeChangeApplier.mergeHr` keeps `current.pickupPoint()` and `current.specialAssistance()`. `UPDATE_HR` does not set those columns. Tests: `EmployeeChangeApplierTest.hrChangeNeverCopiesTransportFields`, `OrderingScenariosTest.hrUpdateDoesNotChangeTransportFields`, `BatchIngestTest.hrUpdateDoesNotOverwriteTransportFields` |
| Transport does not write HR fields | `EmployeeChangeApplierTest.transportChangeNeverCopiesHrFields`. `UPDATE_TRANSPORT` sets only `pickup_point`, `special_assistance`, `transport_version`, `updated_at` |
| Conflict rules match the code | `docs/CONFLICT_RULES.md`: stale is `<=`, the SQL predicate shown is `hr_version < :version`, the idempotency key includes `channel`, a placeholder writes `processed_event` and no outbox row, HR and transport field lists match `mergeHr` / `mergeTransport` |

### Outbox

| Requirement | Test |
| --- | --- |
| One row per applied change, same transaction | `OutboxPollerTest.appliedChangeWritesOneOutboxRowAndNoOpsWriteNone`. `insertOutbox` runs inside `RequiresNewRecordWork` (`PROPAGATION_REQUIRES_NEW`) with the employee update |
| None for stale, duplicate, or rejected | same test |
| Rollback leaves none | `OutboxPollerTest.rolledBackTransactionLeavesNoOutboxRow` |
| Monotonic ids | same applied-change test (V5 `bigint identity`) |
| Payload has no personal values | `OutboxPollerTest.outboxPayloadOmitsPersonalValues`. Payload is `employeeId` plus `changedFields` names from `ChangedFields` |
| `FOR UPDATE SKIP LOCKED` | `OutboxPoller.CLAIM` ends with `for update of o skip locked`. `OutboxPollerTest.twoPollersPublishEachRowOnceAndSkipNone` |
| Per-employee order across two slow pollers | `OutboxPollerTest.twoSlowPollersPreservePerEmployeeOrderWithoutGaps` exists and passes. Removing the `NOT EXISTS` guard did not fail it. See section 3. The SQL guard is present; the test does not prove it |
| Retry, backoff, `FAILED` | `OutboxPollerTest.publishFailsTwiceThenSucceeds`, `exhaustedPublishEndsFailedWithoutBlockingOtherEmployees`. Production settings: max 5 attempts, base 500 ms, max delay 30 s (`application.yml`). Test settings use 0 ms base and max 3 so the test does not sleep |
| Placeholder emits nothing | `OutboxPollerTest.placeholderCreationEmitsNoEventUntilActivation`. `EmployeeChangeService` skips `insertOutbox` when status is `PLACEHOLDER` |
| Crash before publish, consumer dedupes | `OutboxPollerTest.crashAfterCommitBeforePublishStillDeliversOnceToADedupingConsumer` |

### Reconciliation

| Requirement | Test |
| --- | --- |
| Streaming parse | `ExportCsv` reads the stream and does not return a materialized list. `ExportCsvTest.parsesQuotedFieldsAndSkipsTheHeader`. The 200,000-row benchmark completes under a 256 MiB cap |
| Hash per record and hashed buckets | `RosterHash.record` is SHA-256 of employee id, version, name, email, phone, manager, office, shift. `RosterHash.bucket` is the employee-id hash modulo bucket count (default 256). Bucket digest is an XOR plus a count in `ReconciliationService` |
| Only dirty buckets are drilled | `ReconciliationService.classify` returns immediately when `!dirty.get(bucket)` (line 233). `ReconciliationRunTest.identicalExportIsAnEmptyDiffAndChangesNothing` leaves the outbox count unchanged. There is no test that counts second-pass reads per bucket |
| Live event is not overwritten | `ReconciliationRunTest.liveHrEventBetweenDiffAndApplyIsNotDeactivated`. Deactivate SQL is `status = 'DEACTIVATED' where ... hr_version = ? and status = 'ACTIVE'` |
| Brake halts and deactivates nobody | `ReconciliationRunTest.cutoffExportTripsTheSafetyBrake` (80,000 missing of 200,000, ratio 0.40, status `HALTED`, reason `MISSING_RATIO_EXCEEDED`) |
| Missing count on GET | response fields `missingCount`, `missingRatio`, `threshold`, `haltReason` on `ReconciliationRunResponse`. Asserted by the brake test and `missingRatioThresholdIsConfigurable` |
| Threshold 0.10 and 0.50 | `ReconciliationRunTest.missingRatioThresholdIsConfigurable` |
| Approve is operator-only and single-use | `ReconciliationRunTest.approvalAppliesDeactivationsOnce`: anonymous 401, wrong password 401, approve 200 `APPROVED`, second approve 409. `SecurityConfig` requires `ROLE_OPERATOR` only on `POST /reconciliation/runs/*/approve` |
| `PLACEHOLDER` excluded from the ratio | same threshold test (`emp-ghost` is not in `activeCount`). The store scan is `status = 'ACTIVE'` |
| Benchmark | `ReconciliationBenchmarkTest.identicalExportDiffsTwoHundredThousandRowsUnderTheHeapCap`, Surefire execution `reconciliation-benchmark` |

### Privacy

**PARTIAL.** Personal values are kept out of the log lines, error bodies, outbox payloads, and the halt metric. Field names `name`, `email`, `phone`, and `pickupPoint` are logged and stored as `changedFields`.

Log calls reviewed in `src/main` (these are all of them):

| Location | Arguments |
| --- | --- |
| `SyncEventService` lines 37, 46, 55 | `tenantId`, `employeeId`, `sourceVersion`, `reasonCode` |
| `LoggingEventPublisher` line 24 | `eventId`, `tenantId`, `aggregateId`, `eventType`, `changedFields` (names) |
| `OutboxPoller` lines 125 and 146 | `eventId`, `tenantId`, `aggregateId`, attempts, exception class simple name |
| `ReconciliationService` line 112 | `runId`, `tenantId`, `missingCount` |
| `ReconciliationService` line 175 | run id, tenant, counts, ratio, threshold, halt reason |
| `ReconciliationService` line 190 | run id, tenant, status, counts |
| `ReconciliationService` line 206 | run id, tenant, exception class simple name |
| `ReconciliationService` line 540 | fixed string `reconciliation temp file was not deleted` |

Exception messages reviewed: `BatchTooLargeException` (max event count), `BodyTooLargeException` (max bytes), `ReconciliationConflictException` ("Reconciliation run cannot be approved"), `ReconciliationNotFoundException` ("Reconciliation run was not found"), `ReconciliationException` strings "Invalid reconciliation export", "Invalid sourceVersion for employee {id}", "Reconciliation export has no rows", "Reconciliation export contains more than one tenant", "Could not read reconciliation export". None include a phone, email, name, or pickup value. `EmployeeFields.toString()` returns `EmployeeFields[redacted]`.

`SampleHrBatch.assertNoPersonalData` rejects `@leak.example`, `not-a-phone`, `+1555`, `12 Secret Home Lane`, and `SecretLegalName`. `BatchIngestTest.batchOfTenAcceptsSevenAndRejectsThreeWithoutPersonalData` checks every captured log message. Adding `name=SecretLegalName` to the applied log failed that test and `SyncEventsHttpTest.batchOfTenAcceptsSevenAndRejectsThreeWithoutPersonalData` (`/tmp/mut-log-leak.log`: BatchIngest 4 tests, 1 failure; HTTP test 1 failure). The assertion would not fail on the field name `email` alone, because it looks for the fixture markers.

The halt metric tag is `reason=MISSING_RATIO_EXCEEDED` with no tenant tag (`ReconciliationRunTest.missingRatioThresholdIsConfigurable` checks the actuator body).

Fix: stop putting field names that are personal (`name`, `email`, `phone`, `pickupPoint`) into the publisher log line, or document that the payload lists field names and the leak test covers values only.

## 3. Mutation checks

Each change was compiled with `mvn -B test-compile surefire:test -Dtest=...` and then reverted with `git checkout -- .`. After every revert, `git diff` was empty. The only untracked file during the rerun was the temporary scale test, which has since been deleted.

| Mutation | Tests | Result |
| --- | --- | --- |
| SQL stale predicate `<` changed to `<=` only | `OrderingScenariosTest`, `EmployeeChangeApplierTest` | **Not caught.** 16 tests, 0 failures, `BUILD SUCCESS`. The applier rejects `<=` before SQL, so the SQL comparator is unreachable |
| Stale check removed in the applier and both SQL predicates removed | same | **Caught.** 16 tests, 6 failures, 1 error. `equalOrLowerVersionIsStale`, `updateBeforeCreateRecordsATombstoneAtThatVersion`, `deactivateBeforeCreateStaysDeactivatedUntilANewerCreate` expected `STALE_IGNORED`, was null. `laterOfficeTransferWinsWhenAnEarlierChangeArrivesAfterIt` and `officeTransferBeforeAnEarlierShiftChangeKeepsTheVersion7Shift` expected `IGNORED`, was `APPLIED`. `concurrentVersionsKeepTheHighestAndDoNotEscape` expected `50`, was `37`. `updateOrDeactivateBeforeCreateRecordsAVersionTombstone` threw `NullPointerException` |
| `insertProcessed(command)` removed | `OutboxPollerTest.appliedChangeWritesOneOutboxRowAndNoOpsWriteNone`, `OrderingScenariosTest.replayingAFeedLeavesTheDatabaseUnchanged` | **Caught only as "not DUPLICATE".** Outbox test expected `DUPLICATE`, was `STALE_IGNORED`. The replay test passed (1 test, 0 failures) because `assertNoOp` allows `STALE_IGNORED` |
| HR merge copies pickup and `UPDATE_HR` sets `pickup_point` | `EmployeeChangeApplierTest.hrChangeNeverCopiesTransportFields`, `OrderingScenariosTest.hrUpdateDoesNotChangeTransportFields`, `BatchIngestTest.hrUpdateDoesNotOverwriteTransportFields` | **Caught.** 3 tests, 3 failures. Expected `Gate 4`, was `Home Lane` or `12 Secret Home Lane` |
| `insertOutbox` skipped | `OutboxPollerTest.appliedChangeWritesOneOutboxRowAndNoOpsWriteNone`, `placeholderCreationEmitsNoEventUntilActivation` | **Caught.** Expected outbox counts 2 and 1, both were 0 (`/tmp/mut-outbox-skip.log`) |
| `NOT EXISTS` removed from `OutboxPoller.CLAIM` | `OutboxPollerTest.twoSlowPollersPreservePerEmployeeOrderWithoutGaps` | **Not caught.** Passed three times (`/tmp/mut-not-exists.log`, `mut-not-exists-retry-1.log`, `mut-not-exists-retry-2.log`), about 43 s each. `drain()` calls `poll(1)`, and the fixture inserts employees alternately, so `SKIP LOCKED` plus `ORDER BY id` keeps per-employee order without the guard |
| `RequiresNewRecordWork.call` invokes the work with no transaction | `SyncEventsHttpTest.databaseFailureOnRecordFourDoesNotAbortTheRestOfTheBatch` | **Not caught.** 1 test, 0 failures (`/tmp/mut-no-tx-boundary.log`). There is no outer transaction, so autocommit isolates the failed insert |
| `PROPAGATION_REQUIRED` plus `@Transactional` on `SyncEventService.ingest` | same HTTP test | **Caught.** Expected 200, was 500. `UnexpectedRollbackException: Transaction silently rolled back` (`/tmp/mut-shared-tx.log`) |
| `exceedsMissingRatio` always returns false | `cutoffExportTripsTheSafetyBrake`, `missingRatioThresholdIsConfigurable` | **Caught.** Both expected `HALTED`, were `COMPLETED` (`/tmp/mut-brake-never.log`) |
| Halted-status check removed from approve | `approvalAppliesDeactivationsOnce` | **Caught.** Second approve expected 409, was 200 (`/tmp/mut-approval-always.log`) |
| `and hr_version = ?` removed from reconciliation deactivation | `liveHrEventBetweenDiffAndApplyIsNotDeactivated`, `approvalAppliesDeactivationsOnce` | **Caught.** Expected `ACTIVE`, was `DEACTIVATED` (`/tmp/mut-version-guard.log`) |

Test gaps: the SQL `<` versus `<=` predicate, the `NOT EXISTS` ordering guard, and a deleted per-record transaction when no outer transaction exists. Dropping `processed_event` is not caught by the replay test.

## 4. Extra benchmarks

Machine: WSL2 `Linux romrom 6.6.87.2-microsoft-standard-WSL2`, AMD Ryzen 7 7840HS (8 cores / 16 threads), MemTotal 7,760,064 kB, Docker Desktop 29.4.2 (7,578 MB), Temurin 21.0.13+9, PostgreSQL 16.15. Measured 2026-10-30. Numbers are also in the README table.

| Run | Command evidence | Elapsed | Peak heap | `maxMemory` |
| --- | --- | --- | --- | --- |
| Identical 200k, first README measurement | `ReconciliationBenchmarkTest` under the Surefire `reconciliation-benchmark` execution | 1,814 ms | 136,320,024 | 268,435,456 |
| Identical 200k, clean-clone `mvn clean test` | terminal output `elapsedMs=2217 peakHeapBytes=131999968` | 2,217 ms | 131,999,968 | 268,435,456 |
| Mixed ~5% of 200k under `-Xmx256m` | `target/surefire-reports/TEST-com.moveinsync.hrsync.ReconciliationScaleTest.xml`: `label=mixed-200000 elapsedMs=36217 peakHeapBytes=146011416`. Store of 200,000 active rows. Export: 192,000 unchanged, 6,000 version-2 updates, 2,000 creates, 2,000 missing. Assertions required `COMPLETED`, `toUpdate=6000`, `toCreate=2000`, `toDeactivate=2000`. The test passed | 36,217 ms | 146,011,416 | 268,435,456 |
| Identical 400k under `-Xmx256m` | same XML: `label=identical-400000 elapsedMs=4620 peakHeapBytes=135028456` | 4,620 ms | 135,028,456 | 268,435,456 |
| Identical 200k under `-Xmx128m` | `reconciliation benchmark rows=200000 elapsedMs=1388 peakHeapBytes=99754912 maxHeapBytes=134217728`, `BUILD SUCCESS` | 1,388 ms | 99,754,912 | 134,217,728 |
| Identical 200k under `-Xmx48m` | `elapsedMs=1910 peakHeapBytes=46186664 maxHeapBytes=50331648`, `BUILD SUCCESS` | 1,910 ms | 46,186,664 | 50,331,648 |
| Identical 200k under `-Xmx40m` | forked process `Java heap space`, `Tests run: 0`, `BUILD FAILURE` | — | — | — |
| Identical 200k under `-Xmx32m` | `OutOfMemoryError: Java heap space` in `entityManagerFactory`, `BUILD FAILURE` | — | — | — |

96, 80, and 64 MiB also passed (peaks 87,010,048, 77,192,240, 60,712,400). The lowest cap that passed is **48 MiB**.

The 400k peak (135,028,456) is about 1.02× the clone's 200k peak (131,999,968), not double. Elapsed time was about 2.1× (4,620 ms versus 2,217 ms).

**Complexity.** An identical diff is O(E + S) time and O(bucket count) memory: 256 XOR digests, streams not materialized. A bucket is `hash(employeeId) mod 256`. The chance a bucket stays clean is about `exp(-changed/256)`, which is about 0 once a few thousand ids change. A scattered diff then holds O(S) heads and runs one `REQUIRES_NEW` transaction per create, update, and deactivation. That apply path is where it stops scaling. The identical-200k time (1.8–2.2 s) does not describe a 5% diff (36.2 s, peak 146,011,416 bytes).

The temporary test class was deleted after the measurement. It is not in the tree.

## 5. Database and schema

**FAIL — tenant in the key.** `employee` unique `(tenant_id, employee_id)`, `office` unique `(tenant_id, code)`, `processed_event` unique `(tenant_id, employee_id, channel, source_version)`. These three do not:

- `outbox` primary key is identity `id`. `tenant_id` is a column only.
- `import_run` primary key is `id`. `tenant_id` is a column only.
- `import_run_deactivation` primary key is `(run_id, employee_id)` and the table has no `tenant_id` column (V8).

**PARTIAL — every query filters by tenant.** Employee reads and writes in `EmployeeChangeService` all have `tenant_id = ?`. Declared repository methods `findByTenantIdAndEmployeeId`, `countByTenantIdAndStatus`, `findVisibleByTenantIdAndEmployeeId`, and `OfficeRepository.existsByTenantIdAndCode` take a tenant. `EmployeeRepository` extends `JpaRepository`, so `findAll` / `findById` exist and do not filter by tenant; nothing in `src/main` calls them. `JpaEmployeeSyncStore` filters by tenant and is not injected anywhere.

Queries that do not filter by tenant:

- `OutboxPoller.CLAIM` and the attempt updates, which select and update by outbox id across tenants. That is how one poller drains every tenant.
- `ReconciliationService.get` selects `import_run` by id only. Approve locks `select status from import_run where id = ? for update`.
- Deactivation candidates are read by `run_id` only.

**FAIL — poller index is not used.** Indexes present on the explain database (Postgres 16.15, 50,000 employees of which 49,000 `ACTIVE`, 105,000 `PENDING` outbox rows): `idx_outbox_pending` on `(id) WHERE status = 'PENDING'`, leftover `idx_outbox_unpublished` on `created_at WHERE published_at IS NULL`, `idx_outbox_unpublished_id`, and `idx_employee_tenant_active`.

`EXPLAIN (ANALYZE)` of the claim query (`LIMIT 20`): Seq Scan on `outbox` (105,000 rows) + Hash Anti Join + external-merge Sort + LockRows. Execution time 116 ms. `idx_outbox_pending` does not appear. The `NOT EXISTS` anti-join and `next_attempt_at IS NULL OR <= now()` are why.

**PASS — active count.** `EXPLAIN` of `count(*) from employee where tenant_id = 'acme' and status = 'ACTIVE'`: Index Only Scan on `idx_employee_tenant_active`, heap fetches 0, 3.6 ms.

**PARTIAL — reconciliation scan.** `EXPLAIN` of `select employee_id, hr_version, name, email, phone, manager, office_code, shift from employee where tenant_id = 'acme' and status = 'ACTIVE'`: Seq Scan, 49,000 rows, 10 ms. About 98% of the table is active, and the partial index is not covering those columns.

**PARTIAL — apply path.** The hash pass does not query per row. `classify` then calls `syncEvents.ingest` once per create or update (`ReconciliationService.apply`), and each ingest is its own `REQUIRES_NEW` transaction. Deactivations are one update each. A 5% scattered diff is about 10,000 transactions. Identical diffs do no per-row writes.

**FAIL — retention.** Nothing deletes from `processed_event` or `outbox`. No TTL, no scheduled job, no note in the README. Both tables grow with every applied event. `FAILED` outbox rows stay forever and are excluded from the claim only by `status = 'PENDING'`.

## 6. Robustness

Probes were against the compose app on `localhost:8080` after Flyway v8.

**PARTIAL — kill mid-reconciliation.** A run is not resumed. A second run is safe for rows already applied, and it leaves the killed run `RUNNING` forever.

Seeded 2,000 active `k*` employees at `hr_version` 1 and posted a version-2 export. `docker kill hr-sync-audit-app-1` after the first `applied` log line. The client got `curl: (52) Empty reply from server`. Database after the kill:

- `import_run` `9368b034-3d86-4def-8992-a0bd869c9494` status `RUNNING`, count columns null
- `k*` rows: 33 `ACTIVE` at `hr_version` 2, 1,967 still at `hr_version` 1

`insertRun` happens after the scan and before apply (`ReconciliationService.reconcile`). Each apply commits on its own. After `docker start` and the same CSV again, the new run was `COMPLETED` with `unchanged=33`, `toUpdate=1967`, and all 2,000 `k*` rows at `hr_version` 2. The original row stayed `RUNNING` (1 `RUNNING`, 5 `COMPLETED`). The re-run also reported `toDeactivate=81` because the file contained only the `k*` employees, so everyone else was missing and the ratio 0.039 was under 0.30.

**PASS — malformed, wrong width, empty.** Against the compose app:

- empty file: HTTP 400 `{"error":"RECONCILIATION_REJECTED","message":"Reconciliation export has no rows"}`, no `import_run` row
- `acme,emp-1,1,Ada`: HTTP 400 `Invalid reconciliation export` (`ExportCsvTest.rejectsARowWithTheWrongWidth` is the unit test)
- bad version: HTTP 400 `Invalid sourceVersion for employee emp-1`
- two tenants in one file: HTTP 400 `Reconciliation export contains more than one tenant`

These throw during `scanExport`, before `insertRun`.

**PARTIAL — duplicate ids.** A file with `dup-1` at version 1 and again at version 2 returned HTTP 200 `COMPLETED`, `toCreate=2`, `exportRowCount=2`. The table had one row, `hr_version` 2, `ACTIVE`. The file is applied in order and both copies are counted as creates.

**PARTIAL — wrong tenant.** A file for `other-tenant` with no office returned HTTP 200 `COMPLETED`, `toCreate=1`, `tenantId=other-tenant`. No employee row was inserted (`UNKNOWN_OFFICE` is swallowed). `classify` increments `toCreate` before `apply` looks at the ingest outcome (`ReconciliationService` lines 238–240). There is no request parameter for the expected tenant. `acme` rows were not modified.

**PARTIAL — Postgres down for 10 seconds.** `docker pause hr-sync-audit-postgres-1` during `POST /sync/events` for `down-1`. The client timed out at 15 s with HTTP 000 and 0 bytes. Logs: Hikari `Failed to validate connection ... This connection has been closed` on the request thread and on `scheduling-1` (the poller), about every 5 s. After `docker unpause`, `down-1` was `ACTIVE` (the server committed after the client gave up) and the outbox went from 2 `PUBLISHED` + 1 `PENDING` to 4 `PUBLISHED` + 0 `PENDING`. The poller recovered. A client retry of that create is a duplicate. The timed-out request itself has no HTTP response.

**FAIL — two runs at once.** Nothing stops them. Two concurrent identical exports of 80 active employees both returned HTTP 200 `COMPLETED` (about 32 ms each), both with `unchanged=80` and `toDeactivate=1` (an employee who was not in the file). `dup-1` ended `DEACTIVATED`. There is no advisory lock, no partial unique index on a `RUNNING` run per tenant, and `insertRun` does not check for one.

## 7. Gaps versus the brief

| Brief item | Status | What is missing |
| --- | --- | --- |
| Deactivation side effects | PARTIAL | Deactivation writes an outbox row with `changedFields` `["status"]` and sets `status`. No further downstream effect |
| Authentication, roles, tenant isolation | PARTIAL | `ROLE_OPERATOR` and HTTP basic on approve only (`SecurityConfig`). Ingest, `GET /reconciliation/runs/{id}`, `/health`, and actuator metrics are `permitAll`. GET loads a run by UUID with no tenant check. Password default `change-me` |
| Metrics and structured logging | PARTIAL | One counter, `reconciliation.halted`, tag `reason`. Logs are plain SLF4J key=value lines, not JSON |
| Caching | NOT STARTED | No cache |
| Global error handling | PARTIAL | `IngestExceptionHandler` and `ReconciliationExceptionHandler`. `server.error.include-message` is `never`. No catch-all advice |
| Load test with p50 / p99 | NOT STARTED | The reconciliation benchmark is a single-request heap/time measurement, not a latency distribution |
| Kafka or another broker in docker-compose | NOT STARTED | Compose is Postgres and the app. The publisher is `LoggingEventPublisher` |
| Design note (architecture, sequence, failure modes) | NOT STARTED | `docs/` has `CONFLICT_RULES.md` and this audit. No diagram, no sequence, no failure-mode table |
| README: run, assumptions, benchmarks, trade-offs | PARTIAL | Benchmarks are in the README, including the rows from this audit. Run steps, assumptions, and trade-offs are not |
| Demo video | NOT STARTED | None in the repo |
| Public GitHub repo | PARTIAL | `https://api.github.com/repos/rishitbajaj/hr-sync` returned `"private": false`, `"visibility": "public"`. `origin/main` is `93f9fd0`. Local `main` is `e792710`, 17 commits ahead. Phases 2–5 are not on GitHub. This audit did not push |

Suggested order, without starting Phase 6:

1. Push `main` so the public repo matches the work a grader will clone.
2. README sections for how to run, assumptions, and trade-offs. The benchmark table is already measured.
3. A short design note: one architecture sketch, one ingest/reconcile sequence, and a failure-mode table (kill mid-run, Postgres blip, two runs, bad CSV). This audit is most of that table.
4. Tenant isolation and roles on ingest and on `GET /reconciliation/runs/{id}`. Remove the `change-me` default.
5. One reconciliation run per tenant at a time, and mark an abandoned `RUNNING` row `FAILED` on startup.
6. Structured logs, a couple of timers, and a small load test that reports p50 and p99.
7. Retention for `processed_event` and published outbox rows, and a poller index the claim query can use.
8. A broker in compose, then caching, then the demo video.

## 8. Summary

### Top 5 grading risks

1. The public repo is the skeleton commit. A clone of GitHub does not contain Phases 2–5. Local `main` is 17 commits ahead and was not pushed.
2. Authentication is the approve endpoint only. Ingest and `GET /reconciliation/runs/{id}` are open, and the GET query does not filter by tenant. The operator password defaults to `change-me`.
3. Two reconciliations for one tenant can both run and both deactivate. A kill mid-apply leaves a `RUNNING` row forever and a partial update. Rejected creates are still counted in `toCreate`, so a file for an unknown tenant reports a completed create and inserts nobody.
4. The per-employee poller guard is untested (removing `NOT EXISTS` passed three times), and at 105,000 pending rows the claim query is a seq scan plus hash anti-join (116 ms for 20 rows). `processed_event` and `outbox` have no retention.
5. The brief's design note, run/assumptions/trade-offs, load test, broker, and demo are absent. The identical-200k figure of 1.8 s does not describe a 5% diff, which took 36.2 s on the same machine.

### One-line fix for every FAIL or PARTIAL

| Item | Fix |
| --- | --- |
| `.gitignore` | Add `.env` |
| Credentials in source | Drop the `hrsync` and `change-me` defaults; require env vars |
| Poller order test | Make `twoSlowPollers` claim two pending rows of the same employee while one publish is in flight, so removing `NOT EXISTS` fails |
| Privacy field names | Keep values out (already true) and stop logging `changedFields` names, or state that the test covers values |
| SQL `<` versus `<=` | Add a test that reaches the SQL predicate, or assert the SQL text |
| Replay versus duplicate | Make `assertNoOp` on the replay test require `DUPLICATE` |
| Per-record transaction deletion | Assert the transaction template is used, or wrap the test in an outer transaction so autocommit cannot hide the bug |
| Tenant in keys | Put `tenant_id` on `import_run_deactivation` and into the uniqueness of `outbox` / `import_run`, or document why the surrogate id is the key |
| Queries without tenant | Filter `GET /reconciliation/runs/{id}` by the caller's tenant |
| Poller index | Rewrite the claim so `idx_outbox_pending` is usable, then re-check `EXPLAIN` |
| Reconciliation seq scan | Accept it at 98% active, or add a covering index if the column list stays wide |
| Per-row apply | Batch the apply, or document the one-transaction-per-change limit next to the 36 s mixed number |
| Retention | Delete or archive `processed_event` and published/failed outbox rows on a schedule |
| Kill mid-run | On startup, mark `RUNNING` runs `FAILED`. Do not pretend they resume |
| Duplicate ids in an export | Reject the file, or count one employee |
| Wrong tenant / swallowed create | Take the tenant from the caller, and do not increment `toCreate` unless ingest returns `APPLIED` |
| Postgres blip | Return 503 when the pool is down; the poller already retries |
| Two runs at once | A partial unique index on `(tenant_id) WHERE status = 'RUNNING'`, or an advisory lock |
| Deactivation side effects | Document that the outbox `status` event is the side effect, or add the downstream action the brief asks for |
| Auth and tenant isolation | Require a role on ingest and GET, and scope queries by tenant |
| Metrics and structured logs | JSON logs and timers around ingest and reconcile |
| Global errors | One advice for unexpected exceptions that still hides the message |
| README sections | Add run, assumptions, and trade-offs beside the benchmark table |
| Public repo | Push `main`. Do not force-push |

NOT STARTED items (caching, p50/p99 load test, Kafka, design note, demo video) are listed in section 7.

### Claims the code does not back

`docs/CONFLICT_RULES.md` matches the code that was read: stale `<=`, SQL `hr_version < :version`, channel in the idempotency key, placeholder writes no outbox row, field ownership as implemented.

The README identical-200k row (1.814 s, peak 136,320,024 bytes, 256 MiB cap, the machine paragraph) matches the measurement recorded with that commit. The clean-clone rerun on the same machine was 2.217 s and 131,999,968 bytes. Both are now in the table. The 400k, mixed, 128 MiB, and 48 MiB rows are the measurements in section 4. No README number was invented.

Nothing in the README claims a broker, a load-test percentile, tenant-scoped authentication, or that a killed reconciliation resumes. Those gaps are omissions, not false claims.
