package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.TenantMismatchException;
import com.moveinsync.hrsync.auth.TenantRequiredException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class TenantExceptionHandler {

    @ExceptionHandler(TenantMismatchException.class)
    public ResponseEntity<ApiError> mismatch() {
        return ResponseEntity.status(403).body(new ApiError("FORBIDDEN", "Forbidden"));
    }

    @ExceptionHandler(TenantRequiredException.class)
    public ResponseEntity<ApiError> required() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "A tenant is required"));
    }
}
