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
    }
}
