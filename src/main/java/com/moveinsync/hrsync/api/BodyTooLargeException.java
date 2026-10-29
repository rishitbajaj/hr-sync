package com.moveinsync.hrsync.api;

public class BodyTooLargeException extends RuntimeException {

    private final int maxBodyBytes;

    public BodyTooLargeException(int maxBodyBytes) {
        super("Request body exceeds the maximum of " + maxBodyBytes + " bytes");
        this.maxBodyBytes = maxBodyBytes;
    }

    public int maxBodyBytes() {
        return maxBodyBytes;
    }
}
