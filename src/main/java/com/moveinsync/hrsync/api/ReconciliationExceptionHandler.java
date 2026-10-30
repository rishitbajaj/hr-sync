package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.service.ReconciliationConflictException;
import com.moveinsync.hrsync.service.ReconciliationException;
import com.moveinsync.hrsync.service.ReconciliationNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ReconciliationExceptionHandler {

    @ExceptionHandler(ReconciliationException.class)
    public ResponseEntity<ApiError> rejected(ReconciliationException exception) {
        return ResponseEntity.badRequest().body(new ApiError("RECONCILIATION_REJECTED", exception.getMessage()));
    }

    @ExceptionHandler(ReconciliationConflictException.class)
    public ResponseEntity<ApiError> conflict(ReconciliationConflictException exception) {
        return ResponseEntity.status(409).body(new ApiError("CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler(ReconciliationNotFoundException.class)
    public ResponseEntity<ApiError> missing(ReconciliationNotFoundException exception) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", exception.getMessage()));
    }
}
