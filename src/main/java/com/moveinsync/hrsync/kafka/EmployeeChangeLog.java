package com.moveinsync.hrsync.kafka;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class EmployeeChangeLog {

    private final Set<Long> seen = new HashSet<>();
    private final Map<String, Long> lastSequence = new HashMap<>();
    private final Map<String, Long> lastVersion = new HashMap<>();
    private int read;
    private int duplicates;
    private int orderViolations;
    private int malformed;
    private int stale;

    public void accept(long eventId, String key, long sequence) {
        accept(eventId, key, sequence, false, 0);
    }

    public void accept(long eventId, String key, long sequence, long sourceVersion) {
        accept(eventId, key, sequence, true, sourceVersion);
    }

    private void accept(long eventId, String key, long sequence, boolean checkVersion, long sourceVersion) {
        read++;
        if (!seen.add(eventId)) {
            duplicates++;
            return;
        }
        Long previousVersion = lastVersion.get(key);
        Long previousSequence = lastSequence.get(key);
        if (checkVersion
                && previousVersion != null
                && previousSequence != null
                && sourceVersion < previousVersion
                && sequence < previousSequence) {
            stale++;
            return;
        }
        if (checkVersion && (previousVersion == null || sourceVersion >= previousVersion)) {
            lastVersion.put(key, sourceVersion);
        }
        lastSequence.put(key, sequence);
        if (previousSequence != null && sequence < previousSequence) {
            orderViolations++;
        }
    }

    public void markMalformed() {
        read++;
        malformed++;
    }

    public int read() {
        return read;
    }

    public int unique() {
        return seen.size();
    }

    public int duplicates() {
        return duplicates;
    }

    public int orderViolations() {
        return orderViolations;
    }

    public int malformed() {
        return malformed;
    }

    public int stale() {
        return stale;
    }

    public String summary() {
        return "read=" + read
                + " unique=" + unique()
                + " duplicates=" + duplicates
                + " orderViolations=" + orderViolations
                + " malformed=" + malformed
                + " stale=" + stale;
    }
}
