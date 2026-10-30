package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.api.ReconciliationRunResponse;
import com.moveinsync.hrsync.config.ReconciliationSettings;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.RosterHash;
import com.moveinsync.hrsync.domain.SyncEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationService {

    static final String HALT_REASON = "MISSING_RATIO_EXCEEDED";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private static final String ACTIVE = """
            select employee_id, hr_version, name, email, phone, manager, office_code, shift
            from employee
            where tenant_id = ? and status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbc;
    private final ReconciliationSettings settings;
    private final ReconciliationHooks hooks;
    private final SyncEventService syncEvents;
    private final EmployeeChangeService changes;
    private final Clock clock;
    private final TransactionTemplate reads;
    private final MeterRegistry meters;

    public ReconciliationService(
            JdbcTemplate jdbc,
            ReconciliationSettings settings,
            ReconciliationHooks hooks,
            SyncEventService syncEvents,
            EmployeeChangeService changes,
            Clock clock,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters
    ) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.hooks = hooks;
        this.syncEvents = syncEvents;
        this.changes = changes;
        this.clock = clock;
        this.reads = new TransactionTemplate(transactionManager);
        this.meters = meters;
    }

    public ReconciliationRunResponse run(InputStream csv) {
        Path temp = tempFile();
        try {
            copy(csv, temp);
            return reconcile(temp);
        } finally {
            delete(temp);
        }
    }

    public ReconciliationRunResponse approve(UUID id) {
        reads.executeWithoutResult(status -> {
            List<String> current = jdbc.query(
                    "select status from import_run where id = ? for update",
                    (rs, row) -> rs.getString("status"),
                    id
            );
            if (current.isEmpty()) {
                throw new ReconciliationNotFoundException();
            }
            if (!"HALTED".equals(current.get(0))) {
                throw new ReconciliationConflictException();
            }
            applySavedDeactivations(id);
            jdbc.update(
                    "update import_run set status = 'APPROVED', finished_at = ? where id = ?",
                    Timestamp.from(clock.instant()),
                    id
            );
        });
        ReconciliationRunResponse response = get(id);
        log.info(
                "reconciliation approved runId={} tenantId={} missingCount={}",
                response.id(),
                response.tenantId(),
                response.missingCount()
        );
        return response;
    }

    public ReconciliationRunResponse get(UUID id) {
        List<ReconciliationRunResponse> rows = jdbc.query(
                """
                        select id, tenant_id, status, unchanged_count, create_count, update_count,
                               deactivate_count, missing_count, active_employee_count, export_row_count,
                               missing_ratio, threshold, halt_reason
                        from import_run
                        where id = ?
                        """,
                (rs, row) -> mapRun(rs),
                id
        );
        if (rows.isEmpty()) {
            throw new ReconciliationNotFoundException();
        }
        return rows.get(0);
    }

    private ReconciliationRunResponse reconcile(Path temp) {
        String tenantId = declaredTenant(temp);
        UUID id = insertRun(tenantId);
        try {
            return reconcileClaimed(temp, tenantId, id);
        } catch (RuntimeException exception) {
            markFailed(id, tenantId, exception);
            throw exception;
        }
    }

    private ReconciliationRunResponse reconcileClaimed(Path temp, String tenantId, UUID id) {
        int bucketCount = bucketCount();
        BucketDigest[] exported = digests(bucketCount);
        ExportScan scan = scanExport(temp, exported);
        BucketDigest[] stored = digests(bucketCount);
        scanStore(tenantId, (employeeId, version, name, email, phone, manager, office, shift) ->
                stored[RosterHash.bucket(employeeId, bucketCount)].add(
                        RosterHash.record(employeeId, version, name, email, phone, manager, office, shift)
                ));

        BitSet dirty = new BitSet(bucketCount);
        int unchanged = 0;
        int activeCount = 0;
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            activeCount += stored[bucket].count();
            if (exported[bucket].matches(stored[bucket])) {
                unchanged += exported[bucket].count();
            } else {
                dirty.set(bucket);
            }
        }

        Map<String, Head> heads = dirty.isEmpty()
                ? Map.of()
                : loadHeads(tenantId, dirty, bucketCount);
        hooks.beforeApply();
        Diff diff = dirty.isEmpty()
                ? new Diff(unchanged, 0, 0, 0)
                : classify(temp, tenantId, dirty, bucketCount, heads, unchanged);
        if (exceedsMissingRatio(diff.toDeactivate(), activeCount)) {
            saveCandidates(id, heads);
            finish(id, "HALTED", diff, activeCount, scan.rowCount(), HALT_REASON);
            meters.counter("reconciliation.halted", "reason", HALT_REASON).increment();
            ReconciliationRunResponse halted = get(id);
            log.warn(
                    "reconciliation halted runId={} tenantId={} missingCount={} activeCount={} ratio={} threshold={} reason={}",
                    halted.id(),
                    halted.tenantId(),
                    halted.missingCount(),
                    halted.activeCount(),
                    halted.missingRatio(),
                    halted.threshold(),
                    halted.haltReason()
            );
            return halted;
        }
        deactivateMissing(tenantId, heads);
        finish(id, "COMPLETED", diff, activeCount, scan.rowCount(), null);
        ReconciliationRunResponse response = get(id);
        log.info(
                "reconciliation runId={} tenantId={} status={} unchanged={} toCreate={} toUpdate={} toDeactivate={} missingCount={} activeCount={} exportRowCount={}",
                response.id(),
                response.tenantId(),
                response.status(),
                response.unchanged(),
                response.toCreate(),
                response.toUpdate(),
                response.toDeactivate(),
                response.missingCount(),
                response.activeCount(),
                response.exportRowCount()
        );
        return response;
    }

    private String declaredTenant(Path temp) {
        try (InputStream input = Files.newInputStream(temp)) {
            RosterRow first = ExportCsv.first(input);
            if (first == null) {
                throw new ReconciliationException("Reconciliation export has no rows");
            }
            return first.tenantId();
        } catch (IOException exception) {
            throw new ReconciliationException("Could not read reconciliation export");
        }
    }

    private void markFailed(UUID id, String tenantId, RuntimeException exception) {
        jdbc.update(
                """
                        update import_run
                        set status = 'FAILED', finished_at = ?
                        where id = ? and status = 'RUNNING'
                        """,
                Timestamp.from(clock.instant()),
                id
        );
        log.warn(
                "reconciliation failed runId={} tenantId={} error={}",
                id,
                tenantId,
                exception.getClass().getSimpleName()
        );
    }

    private Diff classify(
            Path temp,
            String tenantId,
            BitSet dirty,
            int bucketCount,
            Map<String, Head> heads,
            int unchanged
    ) {
        int[] creates = {0};
        int[] updates = {0};
        int[] matched = {0};
        try (InputStream input = Files.newInputStream(temp)) {
            ExportCsv.parse(input, row -> {
                if (!tenantId.equals(row.tenantId())) {
                    throw new ReconciliationException("Reconciliation export contains more than one tenant");
                }
                int bucket = RosterHash.bucket(row.employeeId(), bucketCount);
                if (!dirty.get(bucket)) {
                    return;
                }
                byte[] hash = hash(row);
                Head head = heads.get(row.employeeId());
                if (head == null) {
                    creates[0]++;
                    apply(row, EventType.CREATE);
                    return;
                }
                head.seen = true;
                if (Arrays.equals(head.hash, hash)) {
                    matched[0]++;
                    return;
                }
                updates[0]++;
                apply(row, EventType.UPDATE);
            });
        } catch (IOException exception) {
            throw new ReconciliationException("Could not read reconciliation export");
        }
        int missing = 0;
        for (Head head : heads.values()) {
            if (!head.seen) {
                missing++;
            }
        }
        return new Diff(unchanged + matched[0], creates[0], updates[0], missing);
    }

    private void saveCandidates(UUID runId, Map<String, Head> heads) {
        List<Head> missing = new ArrayList<>();
        for (Head head : heads.values()) {
            if (!head.seen) {
                missing.add(head);
            }
        }
        int batch = 500;
        String sql = """
                insert into import_run_deactivation (run_id, employee_id, hr_version)
                values (?, ?, ?)
                """;
        for (int start = 0; start < missing.size(); start += batch) {
            int offset = start;
            int size = Math.min(batch, missing.size() - start);
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    Head head = missing.get(offset + index);
                    statement.setObject(1, runId);
                    statement.setString(2, head.employeeId);
                    statement.setLong(3, head.hrVersion);
                }

                @Override
                public int getBatchSize() {
                    return size;
                }
            });
        }
    }

    private void applySavedDeactivations(UUID runId) {
        String tenantId = jdbc.queryForObject(
                "select tenant_id from import_run where id = ?",
                String.class,
                runId
        );
        jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "select employee_id, hr_version from import_run_deactivation where run_id = ?"
            );
            statement.setFetchSize(500);
            statement.setObject(1, runId);
            return statement;
        }, (ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                changes.deactivateIfUnchanged(tenantId, rs.getString("employee_id"), rs.getLong("hr_version"));
            }
            return null;
        });
    }

    private void deactivateMissing(String tenantId, Map<String, Head> heads) {
        for (Head head : heads.values()) {
            if (!head.seen) {
                changes.deactivateIfUnchanged(tenantId, head.employeeId, head.hrVersion);
            }
        }
    }

    private boolean exceedsMissingRatio(int missing, int active) {
        return active > 0 && (double) missing / active > settings.getMaxMissingRatio();
    }

    private void apply(RosterRow row, EventType type) {
        syncEvents.ingest(List.of(new SyncEvent(
                row.tenantId(),
                row.employeeId(),
                row.sourceVersion(),
                type,
                new EmployeeFields(
                        row.name(),
                        row.email(),
                        row.phone(),
                        row.manager(),
                        row.officeCode(),
                        null,
                        null,
                        row.shift()
                )
        )));
    }

    private ExportScan scanExport(Path temp, BucketDigest[] buckets) {
        String[] tenant = new String[1];
        int[] rows = {0};
        try (InputStream input = Files.newInputStream(temp)) {
            ExportCsv.parse(input, row -> {
                if (tenant[0] == null) {
                    tenant[0] = row.tenantId();
                } else if (!tenant[0].equals(row.tenantId())) {
                    throw new ReconciliationException("Reconciliation export contains more than one tenant");
                }
                buckets[RosterHash.bucket(row.employeeId(), buckets.length)].add(hash(row));
                rows[0]++;
            });
        } catch (IOException exception) {
            throw new ReconciliationException("Could not read reconciliation export");
        }
        if (tenant[0] == null) {
            throw new ReconciliationException("Reconciliation export has no rows");
        }
        return new ExportScan(tenant[0], rows[0]);
    }

    private Map<String, Head> loadHeads(String tenantId, BitSet dirty, int bucketCount) {
        Map<String, Head> heads = new HashMap<>();
        scanStore(tenantId, (employeeId, version, name, email, phone, manager, office, shift) -> {
            int bucket = RosterHash.bucket(employeeId, bucketCount);
            if (!dirty.get(bucket)) {
                return;
            }
            heads.put(
                    employeeId,
                    new Head(
                            employeeId,
                            version,
                            RosterHash.record(employeeId, version, name, email, phone, manager, office, shift)
                    )
            );
        });
        return heads;
    }

    private void scanStore(String tenantId, StoreConsumer consumer) {
        reads.executeWithoutResult(status -> jdbc.query(connection -> {
            PreparedStatement statement = connection.prepareStatement(ACTIVE);
            statement.setFetchSize(500);
            statement.setString(1, tenantId);
            return statement;
        }, (ResultSetExtractor<Void>) rs -> {
            while (rs.next()) {
                consumer.accept(
                        rs.getString("employee_id"),
                        rs.getLong("hr_version"),
                        rs.getString("name"),
                        rs.getString("email"),
                        rs.getString("phone"),
                        rs.getString("manager"),
                        rs.getString("office_code"),
                        rs.getString("shift")
                );
            }
            return null;
        }));
    }

    private UUID insertRun(String tenantId) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update(
                    """
                            insert into import_run (id, tenant_id, status, started_at)
                            values (?, ?, 'RUNNING', ?)
                            """,
                    id,
                    tenantId,
                    Timestamp.from(clock.instant())
            );
        } catch (DataAccessException exception) {
            if (isUniqueViolation(exception)) {
                throw new ReconciliationConflictException(
                        "A reconciliation run is already running for this tenant"
                );
            }
            throw exception;
        }
        return id;
    }

    private static boolean isUniqueViolation(DataAccessException exception) {
        if (exception instanceof DuplicateKeyException) {
            return true;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void finish(UUID id, String status, Diff diff, int activeCount, int exportRowCount, String haltReason) {
        BigDecimal ratio = activeCount == 0
                ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(diff.toDeactivate()).divide(BigDecimal.valueOf(activeCount), 6, RoundingMode.HALF_UP);
        BigDecimal threshold = BigDecimal.valueOf(settings.getMaxMissingRatio()).setScale(6, RoundingMode.HALF_UP);
        jdbc.update(
                """
                        update import_run
                        set status = ?,
                            finished_at = ?,
                            active_employee_count = ?,
                            export_row_count = ?,
                            unchanged_count = ?,
                            create_count = ?,
                            update_count = ?,
                            deactivate_count = ?,
                            missing_count = ?,
                            missing_ratio = ?,
                            threshold = ?,
                            halt_reason = ?
                        where id = ?
                        """,
                status,
                Timestamp.from(clock.instant()),
                activeCount,
                exportRowCount,
                diff.unchanged(),
                diff.toCreate(),
                diff.toUpdate(),
                diff.toDeactivate(),
                diff.toDeactivate(),
                ratio,
                threshold,
                haltReason,
                id
        );
    }

    private ReconciliationRunResponse mapRun(ResultSet rs) throws SQLException {
        return new ReconciliationRunResponse(
                rs.getObject("id", UUID.class),
                rs.getString("tenant_id"),
                rs.getString("status"),
                number(rs, "unchanged_count"),
                number(rs, "create_count"),
                number(rs, "update_count"),
                number(rs, "deactivate_count"),
                number(rs, "missing_count"),
                number(rs, "active_employee_count"),
                number(rs, "export_row_count"),
                decimal(rs, "missing_ratio"),
                decimal(rs, "threshold"),
                rs.getString("halt_reason")
        );
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private static int number(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? 0 : value;
    }

    private int bucketCount() {
        int bucketCount = settings.getBucketCount();
        if (bucketCount < 1) {
            throw new ReconciliationException("Reconciliation bucket count must be positive");
        }
        return bucketCount;
    }

    private static BucketDigest[] digests(int bucketCount) {
        BucketDigest[] digests = new BucketDigest[bucketCount];
        for (int index = 0; index < bucketCount; index++) {
            digests[index] = new BucketDigest();
        }
        return digests;
    }

    private static byte[] hash(RosterRow row) {
        return RosterHash.record(
                row.employeeId(),
                row.sourceVersion(),
                row.name(),
                row.email(),
                row.phone(),
                row.manager(),
                row.officeCode(),
                row.shift()
        );
    }

    private static Path tempFile() {
        try {
            return Files.createTempFile("hr-sync-export-", ".csv");
        } catch (IOException exception) {
            throw new ReconciliationException("Could not read reconciliation export");
        }
    }

    private static void copy(InputStream csv, Path temp) {
        try (InputStream input = csv; OutputStream output = Files.newOutputStream(temp)) {
            input.transferTo(output);
        } catch (IOException exception) {
            throw new ReconciliationException("Could not read reconciliation export");
        }
    }

    private static void delete(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException exception) {
            log.warn("reconciliation temp file was not deleted");
        }
    }

    @FunctionalInterface
    private interface StoreConsumer {
        void accept(
                String employeeId,
                long version,
                String name,
                String email,
                String phone,
                String manager,
                String officeCode,
                String shift
        );
    }

    private record ExportScan(String tenantId, int rowCount) {
    }

    private record Diff(int unchanged, int toCreate, int toUpdate, int toDeactivate) {
    }

    private static final class Head {
        private final String employeeId;
        private final long hrVersion;
        private final byte[] hash;
        private boolean seen;

        private Head(String employeeId, long hrVersion, byte[] hash) {
            this.employeeId = employeeId;
            this.hrVersion = hrVersion;
            this.hash = hash;
        }
    }

    private static final class BucketDigest {
        private final byte[] xor = new byte[32];
        private int count;

        private void add(byte[] hash) {
            for (int index = 0; index < xor.length; index++) {
                xor[index] ^= hash[index];
            }
            count++;
        }

        private boolean matches(BucketDigest other) {
            return count == other.count && Arrays.equals(xor, other.xor);
        }

        private int count() {
            return count;
        }
    }
}
