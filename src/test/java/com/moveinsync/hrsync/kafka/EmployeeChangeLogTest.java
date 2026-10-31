package com.moveinsync.hrsync.kafka;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmployeeChangeLogTest {

    @Test
    void duplicatesAreSkippedAndOrderIsPerEmployee() {
        EmployeeChangeLog log = new EmployeeChangeLog();
        log.accept(1, "acme:emp-1", 1);
        log.accept(2, "acme:emp-1", 2);
        log.accept(1, "acme:emp-1", 1);
        log.accept(3, "other:emp-1", 3);
        log.accept(4, "acme:emp-2", 4);
        log.accept(5, "acme:emp-1", 1);

        assertThat(log.read()).isEqualTo(6);
        assertThat(log.unique()).isEqualTo(5);
        assertThat(log.duplicates()).isEqualTo(1);
        assertThat(log.orderViolations()).isEqualTo(1);
        assertThat(log.summary()).doesNotContain("phone").doesNotContain("@");
        assertThat(log.stale()).isZero();
    }

    @Test
    void anOlderSourceVersionIsDiscardedAndARedeliveryIsNotStale() {
        EmployeeChangeLog log = new EmployeeChangeLog();
        log.accept(10, "acme:emp-1", 10, 5);
        log.accept(10, "acme:emp-1", 10, 5);
        log.accept(11, "acme:emp-1", 8, 2);
        log.accept(12, "acme:emp-2", 12, 1);
        log.accept(13, "acme:emp-1", 13, 1);

        assertThat(log.duplicates()).isEqualTo(1);
        assertThat(log.stale()).isEqualTo(1);
        assertThat(log.orderViolations()).isZero();
        assertThat(log.unique()).isEqualTo(4);
        assertThat(log.summary()).contains("stale=1");
    }
}
