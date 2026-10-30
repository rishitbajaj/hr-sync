package com.moveinsync.hrsync.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    @Test
    void aFailedSampleKeepsThePreviousReading() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        when(jdbc.queryForObject(OutboxMetrics.PENDING_DEPTH, Number.class)).thenReturn(4L);
        when(jdbc.queryForObject(OutboxMetrics.OLDEST_PENDING_SECONDS, Number.class)).thenReturn(15.9d);
        when(jdbc.queryForObject(OutboxMetrics.FAILED_DEPTH, Number.class)).thenReturn(2L);
        OutboxMetrics metrics = new OutboxMetrics(jdbc, registry);
        metrics.sample();

        when(jdbc.queryForObject(OutboxMetrics.PENDING_DEPTH, Number.class))
                .thenThrow(new DataAccessResourceFailureException("down"));
        try {
            metrics.sample();
        } catch (DataAccessResourceFailureException ignored) {
            // The sample must fail visibly. It must not replace the last reading with zero.
        }

        assertThat(registry.get("outbox.pending").gauge().value()).isEqualTo(4);
        assertThat(registry.get("outbox.oldest.pending.seconds").gauge().value()).isEqualTo(15);
        assertThat(registry.get("outbox.failed").gauge().value()).isEqualTo(2);
    }
}
