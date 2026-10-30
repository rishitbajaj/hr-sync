package com.moveinsync.hrsync;

import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.Outcome;
import com.moveinsync.hrsync.domain.ReasonCode;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.repo.OfficeEntity;
import com.moveinsync.hrsync.repo.OfficeRepository;
import com.moveinsync.hrsync.service.RetentionJob;
import com.moveinsync.hrsync.service.SyncEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "hr-sync.retention.age=1h",
        "hr-sync.retention.batch-size=2"
})
@Testcontainers
class RetentionJobTest {

    private static final String TENANT = "retain-co";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private RetentionJob retention;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private SyncEventService syncEvents;

    @Autowired
    private OfficeRepository offices;

    @BeforeEach
    void emptyRetentionTables() {
        jdbc.update("delete from outbox");
        jdbc.update("delete from processed_event");
        if (!offices.existsByTenantIdAndCode(TENANT, SampleHrBatch.OFFICE)) {
            offices.save(new OfficeEntity(UUID.randomUUID(), TENANT, SampleHrBatch.OFFICE));
        }
    }

    @Test
    void purgeDeletesOldPublishedRowsAndProcessedEventsInBatchesAndKeepsFailed() {
        Timestamp old = Timestamp.from(clock.instant().minus(Duration.ofHours(2)));
        Timestamp young = Timestamp.from(clock.instant());
        for (int index = 1; index <= 5; index++) {
            insertOutbox("pub-" + index, "PUBLISHED", old, old);
            insertProcessed("proc-" + index, index, old);
        }
        insertOutbox("pub-young", "PUBLISHED", young, young);
        insertOutbox("failed-old", "FAILED", old, null);
        insertOutbox("pending-old", "PENDING", old, null);
        insertProcessed("proc-young", 9, young);

        assertThat(retention.deletePublishedOutbox()).isEqualTo(2);
        assertThat(retention.deleteOldProcessedEvents()).isEqualTo(2);
        assertThat(countOldPublished()).isEqualTo(3);
        assertThat(countStatus("PUBLISHED")).isEqualTo(4);
        assertThat(countStatus("FAILED")).isEqualTo(1);
        assertThat(countStatus("PENDING")).isEqualTo(1);
        assertThat(countOldProcessed()).isEqualTo(3);
        assertThat(countYoungProcessed()).isEqualTo(1);

        assertThat(retention.deletePublishedOutbox()).isEqualTo(2);
        assertThat(retention.deleteOldProcessedEvents()).isEqualTo(2);
        assertThat(countOldPublished()).isEqualTo(1);
        assertThat(countOldProcessed()).isEqualTo(1);

        assertThat(retention.deletePublishedOutbox()).isEqualTo(1);
        assertThat(retention.deleteOldProcessedEvents()).isEqualTo(1);
        assertThat(countOldPublished()).isZero();
        assertThat(countOldProcessed()).isZero();
        assertThat(countStatus("PUBLISHED")).isEqualTo(1);
        assertThat(countStatus("FAILED")).isEqualTo(1);
        assertThat(countStatus("PENDING")).isEqualTo(1);
        assertThat(countYoungProcessed()).isEqualTo(1);

        assertThat(retention.deletePublishedOutbox()).isZero();
        assertThat(retention.deleteOldProcessedEvents()).isZero();
    }

    @Test
    void aPrunedProcessedEventStillIgnoresAReplayBecauseTheStoredVersionIsCurrent() {
        SyncEvent create = new SyncEvent(
                TENANT,
                "emp-retain",
                1L,
                EventType.CREATE,
                new EmployeeFields("Ada", "emp-retain@leak.example", "+15550000001", "M1", SampleHrBatch.OFFICE, null, null, "DAY")
        );
        assertThat(syncEvents.ingest(java.util.List.of(create)).get(0).outcome()).isEqualTo(Outcome.APPLIED);
        jdbc.update(
                """
                        update processed_event
                        set processed_at = ?
                        where tenant_id = ? and employee_id = ?
                        """,
                Timestamp.from(clock.instant().minus(Duration.ofHours(2))),
                TENANT,
                "emp-retain"
        );

        assertThat(retention.deleteOldProcessedEvents()).isEqualTo(1);
        assertThat(countOldProcessed()).isZero();

        var replay = syncEvents.ingest(java.util.List.of(create)).get(0);
        assertThat(replay.outcome()).isEqualTo(Outcome.IGNORED);
        assertThat(replay.reasonCode()).isEqualTo(ReasonCode.STALE_IGNORED);
        assertThat(jdbc.queryForObject(
                "select name from employee where tenant_id = ? and employee_id = ?",
                String.class,
                TENANT,
                "emp-retain"
        )).isEqualTo("Ada");
        assertThat(jdbc.queryForObject(
                "select hr_version from employee where tenant_id = ? and employee_id = ?",
                Long.class,
                TENANT,
                "emp-retain"
        )).isEqualTo(1L);
    }

    private void insertOutbox(String aggregateId, String status, Timestamp created, Timestamp published) {
        jdbc.update(
                """
                        insert into outbox (
                            tenant_id, aggregate_id, event_type, payload, created_at, published_at, status
                        )
                        values (?, ?, 'CREATE', '{}'::jsonb, ?, ?, ?)
                        """,
                TENANT,
                aggregateId,
                created,
                published,
                status
        );
    }

    private void insertProcessed(String employeeId, long version, Timestamp processedAt) {
        jdbc.update(
                """
                        insert into processed_event (
                            id, tenant_id, employee_id, channel, source_version, processed_at
                        )
                        values (?, ?, ?, 'HR', ?, ?)
                        """,
                UUID.randomUUID(),
                TENANT,
                employeeId,
                version,
                processedAt
        );
    }

    private long countOldPublished() {
        Long count = jdbc.queryForObject(
                """
                        select count(*) from outbox
                        where tenant_id = ? and status = 'PUBLISHED' and published_at < ?
                        """,
                Long.class,
                TENANT,
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30)))
        );
        return count == null ? 0 : count;
    }

    private long countStatus(String status) {
        Long count = jdbc.queryForObject(
                "select count(*) from outbox where tenant_id = ? and status = ?",
                Long.class,
                TENANT,
                status
        );
        return count == null ? 0 : count;
    }

    private long countOldProcessed() {
        Long count = jdbc.queryForObject(
                """
                        select count(*) from processed_event
                        where tenant_id = ? and processed_at < ?
                        """,
                Long.class,
                TENANT,
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30)))
        );
        return count == null ? 0 : count;
    }

    private long countYoungProcessed() {
        Long count = jdbc.queryForObject(
                """
                        select count(*) from processed_event
                        where tenant_id = ? and processed_at >= ?
                        """,
                Long.class,
                TENANT,
                Timestamp.from(clock.instant().minus(Duration.ofMinutes(30)))
        );
        return count == null ? 0 : count;
    }
}
