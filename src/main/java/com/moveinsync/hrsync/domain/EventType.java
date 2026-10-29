package com.moveinsync.hrsync.domain;

import java.util.Locale;

public enum EventType {
    CREATE,
    UPDATE,
    DEACTIVATE;

    public static EventType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
