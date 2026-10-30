package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.AuthenticationRejectedException;
import com.moveinsync.hrsync.auth.TenantMismatchException;
import com.moveinsync.hrsync.auth.TenantRequiredException;
import com.moveinsync.hrsync.config.DatabaseSettings;
import com.moveinsync.hrsync.observability.CorrelationIds;
import com.moveinsync.hrsync.service.InfrastructureErrors;
import com.moveinsync.hrsync.service.IngestUnavailableException;
import com.moveinsync.hrsync.service.OutboxConflictException;
import com.moveinsync.hrsync.service.OutboxNotFoundException;
import com.moveinsync.hrsync.service.OutboxRejectedException;
import com.moveinsync.hrsync.service.ReconciliationConflictException;
import com.moveinsync.hrsync.service.ReconciliationException;
import com.moveinsync.hrsync.service.ReconciliationNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final String retryAfterSeconds;

    public ApiExceptionHandler(DatabaseSettings database) {
        this.retryAfterSeconds = Long.toString(Math.max(1, database.getRetryAfter().toSeconds()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception) {
        return badRequest("VALIDATION_FAILED", "Request validation failed");
    }

    @ExceptionHandler({ConstraintViolationException.class, HandlerMethodValidationException.class})
    public ResponseEntity<ApiError> constraints(Exception exception) {
        return badRequest("VALIDATION_FAILED", "Request validation failed");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformed(HttpMessageNotReadableException exception) {
        return badRequest("MALFORMED_JSON", "Request body is not valid JSON");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(415).body(new ApiError("UNSUPPORTED_MEDIA_TYPE", "Unsupported media type"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(405).body(new ApiError("METHOD_NOT_ALLOWED", "Method not allowed"));
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiError> missingRoute(Exception exception) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Not found"));
    }

    @ExceptionHandler({BodyTooLargeException.class, MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> tooLarge(Exception exception) {
        String message = exception instanceof BodyTooLargeException body
                ? body.getMessage()
                : "Request body exceeds the maximum size";
        return ResponseEntity.status(413).body(new ApiError("REQUEST_TOO_LARGE", message));
    }

    @ExceptionHandler(BatchTooLargeException.class)
    public ResponseEntity<ApiError> batchTooLarge(BatchTooLargeException exception) {
        return badRequest("BATCH_TOO_LARGE", exception.getMessage());
    }

    @ExceptionHandler(AuthenticationRejectedException.class)
    public ResponseEntity<ApiError> rejected() {
        return ResponseEntity.status(401).body(new ApiError("UNAUTHORIZED", "Invalid username or password"));
    }

    @ExceptionHandler(TenantMismatchException.class)
    public ResponseEntity<ApiError> mismatch() {
        return ResponseEntity.status(403).body(new ApiError("FORBIDDEN", "Forbidden"));
    }

    @ExceptionHandler(TenantRequiredException.class)
    public ResponseEntity<ApiError> tenantRequired() {
        return badRequest("INVALID_REQUEST", "A tenant is required");
    }

    @ExceptionHandler(EmployeeController.EmployeeNotFoundException.class)
    public ResponseEntity<ApiError> employeeMissing() {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", "Employee not found"));
    }

    @ExceptionHandler(OutboxRejectedException.class)
    public ResponseEntity<ApiError> outboxRejected(OutboxRejectedException exception) {
        return badRequest("INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler({OutboxConflictException.class, ReconciliationConflictException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException exception) {
        return ResponseEntity.status(409).body(new ApiError("CONFLICT", exception.getMessage()));
    }

    @ExceptionHandler({OutboxNotFoundException.class, ReconciliationNotFoundException.class})
    public ResponseEntity<ApiError> notFound(RuntimeException exception) {
        return ResponseEntity.status(404).body(new ApiError("NOT_FOUND", exception.getMessage()));
    }

    @ExceptionHandler(ReconciliationException.class)
    public ResponseEntity<ApiError> reconciliation(ReconciliationException exception) {
        return badRequest("RECONCILIATION_REJECTED", exception.getMessage());
    }

    @ExceptionHandler(IngestUnavailableException.class)
    public ResponseEntity<SyncBatchResponse> ingestUnavailable(IngestUnavailableException exception) {
        log.error("database unavailable during ingest");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                .body(SyncBatchResponse.from(exception.results(), CorrelationIds.current()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception exception) {
        if (InfrastructureErrors.isInfrastructure(exception)) {
            log.error("database unavailable");
            return ResponseEntity.status(503)
                    .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds)
                    .body(new ApiError("UNAVAILABLE", "Database is unavailable"));
        }
        log.error("unexpected error type={}", exception.getClass().getName());
        return ResponseEntity.status(500).body(new ApiError("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private static ResponseEntity<ApiError> badRequest(String code, String message) {
        return ResponseEntity.badRequest().body(new ApiError(code, message));
    }

}
