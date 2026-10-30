package com.moveinsync.hrsync.service;

public class OutboxNotFoundException extends RuntimeException {

    public OutboxNotFoundException(String message) {
        super(message);
    }
}
