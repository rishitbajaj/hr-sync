package com.moveinsync.hrsync.api;

public class BatchTooLargeException extends RuntimeException {

    public BatchTooLargeException(int maxBatchSize) {
        super("Batch exceeds the maximum of " + maxBatchSize + " events");
    }
}
