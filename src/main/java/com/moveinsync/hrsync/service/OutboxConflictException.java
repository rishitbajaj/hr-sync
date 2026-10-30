package com.moveinsync.hrsync.service;

public class OutboxConflictException extends RuntimeException {

    public OutboxConflictException(String message) {
        super(message);
    }
}
