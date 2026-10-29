package com.moveinsync.hrsync.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class IngestExceptionHandler {

    @ExceptionHandler(BatchTooLargeException.class)
    public ResponseEntity<ApiError> batchTooLarge(BatchTooLargeException exception) {
        return ResponseEntity.badRequest().body(new ApiError("BATCH_TOO_LARGE", exception.getMessage()));
    }

    @ExceptionHandler(BodyTooLargeException.class)
    public ResponseEntity<ApiError> bodyTooLarge(BodyTooLargeException exception) {
        return ResponseEntity.status(413).body(new ApiError("REQUEST_TOO_LARGE", exception.getMessage()));
    }
}
