package com.moveinsync.hrsync.service;

public class ReconciliationNotFoundException extends RuntimeException {

    public ReconciliationNotFoundException() {
        super("Reconciliation run was not found");
    }
}
