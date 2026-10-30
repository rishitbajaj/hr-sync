package com.moveinsync.hrsync.service;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;

@Component
public class InterruptedRuns implements ApplicationRunner {

    static final String REASON = "INTERRUPTED";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InterruptedRuns(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        markInterrupted();
    }

    public int markInterrupted() {
        return jdbc.update(
                """
                        update import_run
                        set status = 'FAILED', halt_reason = ?, finished_at = ?
                        where status = 'RUNNING'
                        """,
                REASON,
                Timestamp.from(clock.instant())
        );
    }
}
