package com.moveinsync.hrsync.observability;

import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts non-blank values into MDC and removes only those keys when closed.
 */
public final class MdcScope implements AutoCloseable {

    private final List<String> keys = new ArrayList<>();

    private MdcScope() {
    }

    public static MdcScope open(String key, String value) {
        MdcScope scope = new MdcScope();
        scope.put(key, value);
        return scope;
    }

    public MdcScope and(String key, String value) {
        put(key, value);
        return this;
    }

    @Override
    public void close() {
        for (String key : keys) {
            MDC.remove(key);
        }
    }

    private void put(String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        MDC.put(key, value);
        keys.add(key);
    }
}
