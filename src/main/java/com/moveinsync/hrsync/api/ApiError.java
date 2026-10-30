package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.observability.CorrelationIds;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, String correlationId) {

    public ApiError(String error, String message) {
        this(error, message, CorrelationIds.current());
    }
}
