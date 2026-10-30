package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.config.RetentionSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;

@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private static final String DELETE_PUBLISHED_OUTBOX = """
            with doomed as (
                select id
                from outbox
                where status = 'PUBLISHED'
                  and published_at < ?
                order by id
                limit ?
            )
            delete from outbox
            where id in (select id from doomed)
            """;

    private static final String DELETE_PROCESSED_EVENTS = """
            with doomed as (
                select id
                from processed_event
                where processed_at < ?
                order by processed_at, id
                limit ?
            )
            delete from processed_event
            where id in (select id from doomed)
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RetentionSettings settings;
    private final Clock clock;

    public RetentionJob(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            RetentionSettings settings,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.settings = settings;
        this.clock = clock;
    }

    public void run() {
        int batch = settings.getBatchSize();
        if (batch < 1) {
            throw new IllegalStateException("hr-sync.retention.batch-size must be at least 1");
        }
        int outbox = 0;
        int processed = 0;
        while (true) {
            int deletedOutbox = deletePublishedOutbox();
            int deletedProcessed = deleteOldProcessedEvents();
            outbox += deletedOutbox;
            processed += deletedProcessed;
            if (deletedOutbox < batch && deletedProcessed < batch) {
                break;
            }
        }
        if (outbox > 0 || processed > 0) {
            log.info("retention deleted publishedOutbox={} processedEvents={}", outbox, processed);
        }
    }

    public int deletePublishedOutbox() {
        return delete(DELETE_PUBLISHED_OUTBOX);
    }

    public int deleteOldProcessedEvents() {
        return delete(DELETE_PROCESSED_EVENTS);
    }

    private int delete(String sql) {
        Integer deleted = transactions.execute(status -> jdbc.update(
                sql,
                Timestamp.from(clock.instant().minus(settings.getAge())),
                settings.getBatchSize()
        ));
        return deleted == null ? 0 : deleted;
    }
}
