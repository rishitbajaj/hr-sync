package com.moveinsync.hrsync.kafka;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class EmployeeChangeLog {

    private final Set<Long> seen = new HashSet<>();
    private final Map<String, Long> lastSequence = new HashMap<>();
    private int read;
    private int duplicates;
    private int orderViolations;
    private int malformed;

    public void accept(long eventId, String key, long sequence) {
        read++;
        if (!seen.add(eventId)) {
            duplicates++;
            return;
        }
        Long previous = lastSequence.put(key, sequence);
        if (previous != null && sequence < previous) {
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

    public String summary() {
        return "read=" + read
                + " unique=" + unique()
                + " duplicates=" + duplicates
                + " orderViolations=" + orderViolations
                + " malformed=" + malformed;
    }
}
