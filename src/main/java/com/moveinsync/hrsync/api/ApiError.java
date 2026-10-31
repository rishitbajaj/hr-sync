package com.moveinsync.hrsync.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moveinsync.hrsync.observability.CorrelationIds;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Error body. correlationId is present when the request had one.")
public record ApiError(
        @Schema(example = "UNAUTHORIZED") String code,
        @Schema(example = "Authentication required") String message,
        @Schema(example = "req-1") String correlationId
) {

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
