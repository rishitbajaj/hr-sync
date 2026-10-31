package com.moveinsync.hrsync.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "APPLIED stores the change. REJECTED is a validation or constraint failure. IGNORED is DUPLICATE or STALE_IGNORED. RETRYABLE_ERROR is DATABASE_UNAVAILABLE or NOT_PROCESSED and the HTTP status is 503.")
public enum Outcome {
    APPLIED,
    REJECTED,
    IGNORED,
    RETRYABLE_ERROR
}
