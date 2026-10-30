package com.moveinsync.hrsync.service;

public class ReconciliationConflictException extends RuntimeException {

    public ReconciliationConflictException() {
        super("Reconciliation run cannot be approved");
    }
}
