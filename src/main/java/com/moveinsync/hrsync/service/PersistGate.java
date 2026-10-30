package com.moveinsync.hrsync.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class PersistGate {

    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();

    public void afterPersist() {
        RuntimeException pending = failure.getAndSet(null);
        if (pending != null) {
            throw pending;
        }
    }

    public void failNext(RuntimeException failure) {
        this.failure.set(failure);
    }
}
