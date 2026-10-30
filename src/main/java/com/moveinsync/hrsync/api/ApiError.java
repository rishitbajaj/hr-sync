package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.observability.CorrelationIds;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, String correlationId) {

    public ApiError(String code, String message) {
        this(code, message, CorrelationIds.current());
    }

    public static String json(String code, String message) {
        String correlationId = CorrelationIds.current();
        if (correlationId == null) {
            return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        }
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"correlationId\":\""
                + correlationId + "\"}";
    }
}
