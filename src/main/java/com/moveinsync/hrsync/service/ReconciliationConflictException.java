package com.moveinsync.hrsync.service;

public class ReconciliationConflictException extends RuntimeException {

    public ReconciliationConflictException() {
        this("Reconciliation run cannot be approved");
    }

    public ReconciliationConflictException(String message) {
        super(message);
    }
}
