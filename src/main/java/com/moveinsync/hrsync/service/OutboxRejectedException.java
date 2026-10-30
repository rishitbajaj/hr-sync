package com.moveinsync.hrsync.service;

public class OutboxRejectedException extends RuntimeException {

    public OutboxRejectedException(String message) {
        super(message);
    }
}
