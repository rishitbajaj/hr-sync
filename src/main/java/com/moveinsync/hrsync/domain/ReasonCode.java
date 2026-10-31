package com.moveinsync.hrsync.domain;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Omitted when the outcome is APPLIED. RETRYABLE_ERROR uses DATABASE_UNAVAILABLE or NOT_PROCESSED.")
public enum ReasonCode {
    MISSING_REQUIRED_FIELD,
    INVALID_PHONE,
    INVALID_EMAIL,
    INVALID_SHIFT,
    UNKNOWN_OFFICE,
    DUPLICATE,
    STALE_IGNORED,
    APPLY_FAILED,
    DATABASE_UNAVAILABLE,
    NOT_PROCESSED
}
