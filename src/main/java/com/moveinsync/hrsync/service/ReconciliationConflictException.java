package com.moveinsync.hrsync.service;

public class ReconciliationConflictException extends RuntimeException {

    private final String code;

    public ReconciliationConflictException() {
        this("CONFLICT", "Reconciliation run cannot be approved");
    }

    public ReconciliationConflictException(String message) {
        this("CONFLICT", message);
    }

    public ReconciliationConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
