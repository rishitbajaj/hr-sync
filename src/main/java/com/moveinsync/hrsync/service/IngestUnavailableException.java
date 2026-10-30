package com.moveinsync.hrsync.service;

import com.moveinsync.hrsync.domain.IngestResult;

import java.util.List;

public final class IngestUnavailableException extends RuntimeException {

    private final List<IngestResult> results;

    public IngestUnavailableException(List<IngestResult> results) {
        super("ingest retryable");
        this.results = List.copyOf(results);
    }

    public List<IngestResult> results() {
        return results;
    }
}
