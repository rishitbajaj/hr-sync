package com.moveinsync.hrsync.service;

import org.springframework.stereotype.Component;

@Component
public class ReconciliationHooks {

    private volatile Runnable beforeApply = () -> {
    };

    public void beforeApply() {
        beforeApply.run();
    }

    public void setBeforeApply(Runnable beforeApply) {
        this.beforeApply = beforeApply == null ? () -> {
        } : beforeApply;
    }
}
