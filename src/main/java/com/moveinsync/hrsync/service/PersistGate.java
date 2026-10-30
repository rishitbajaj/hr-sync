package com.moveinsync.hrsync.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class PersistGate {

    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger skip = new AtomicInteger();

    public void afterPersist() {
        if (skip.get() > 0) {
            skip.decrementAndGet();
            return;
        }
        RuntimeException pending = failure.getAndSet(null);
        if (pending != null) {
            throw pending;
        }
    }

    public void failNext(RuntimeException failure) {
        this.skip.set(0);
        this.failure.set(failure);
    }

    public void failAfter(int successfulPersists, RuntimeException failure) {
        this.skip.set(successfulPersists);
        this.failure.set(failure);
    }
}
