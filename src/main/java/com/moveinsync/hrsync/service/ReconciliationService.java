package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.api.ReconciliationRunResponse;
import com.moveinsync.hrsync.config.ReconciliationSettings;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.RosterHash;
import com.moveinsync.hrsync.domain.SyncEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ReconciliationService {

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

    public ReconciliationService(
            JdbcTemplate jdbc,
            ReconciliationSettings settings,
            ReconciliationHooks hooks,
            SyncEventService syncEvents,
            EmployeeChangeService changes,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.hooks = hooks;
        this.syncEvents = syncEvents;
        this.changes = changes;
        this.clock = clock;
        this.reads = new TransactionTemplate(transactionManager);
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

    public ReconciliationRunResponse get(UUID id) {
        List<ReconciliationRunResponse> rows = jdbc.query(
                """
                        select id, tenant_id, status, unchanged_count, create_count, update_count,
                               deactivate_count, missing_count, active_employee_count, export_row_count
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
        int bucketCount = bucketCount();
        BucketDigest[] exported = digests(bucketCount);
        ExportScan scan = scanExport(temp, exported);
        BucketDigest[] stored = digests(bucketCount);
        scanStore(scan.tenantId(), (employeeId, version, name, email, phone, manager, office, shift) ->
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
                : loadHeads(scan.tenantId(), dirty, bucketCount);
        UUID id = insertRun(scan.tenantId());
        try {
            hooks.beforeApply();
            Diff diff = dirty.isEmpty()
                    ? new Diff(unchanged, 0, 0, 0)
                    : classify(temp, scan.tenantId(), dirty, bucketCount, heads, unchanged);
            finish(id, "COMPLETED", diff, activeCount, scan.rowCount());
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
        } catch (RuntimeException exception) {
            finish(id, "FAILED", new Diff(unchanged, 0, 0, 0), activeCount, scan.rowCount());
            log.warn(
                    "reconciliation failed runId={} tenantId={} error={}",
                    id,
                    scan.tenantId(),
                    exception.getClass().getSimpleName()
            );
            throw exception;
        }
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
            if (head.seen) {
                continue;
            }
            missing++;
            changes.deactivateIfUnchanged(tenantId, head.employeeId, head.hrVersion);
        }
        return new Diff(unchanged + matched[0], creates[0], updates[0], missing);
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
        jdbc.update(
                """
                        insert into import_run (id, tenant_id, status, started_at)
                        values (?, ?, 'RUNNING', ?)
                        """,
                id,
                tenantId,
                Timestamp.from(clock.instant())
        );
        return id;
    }

    private void finish(UUID id, String status, Diff diff, int activeCount, int exportRowCount) {
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
                            missing_count = ?
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
                number(rs, "export_row_count")
        );
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
