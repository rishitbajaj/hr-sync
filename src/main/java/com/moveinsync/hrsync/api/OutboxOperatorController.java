package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.config.OpenApiConfig;
import com.moveinsync.hrsync.service.OutboxOperatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class OutboxOperatorController {

    private final OutboxOperatorService outbox;

    public OutboxOperatorController(OutboxOperatorService outbox) {
        this.outbox = outbox;
    }

    @Operation(
            summary = "List failed outbox rows",
            description = "Operator. Each row is employeeId, eventId, attempts, and reasonCode.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "Failed rows for the tenant. Empty when there are none.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an operator.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/outbox/failed")
    public List<OutboxOperatorService.FailedOutboxRow> failed(
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return outbox.failed(Callers.tenant(tenantId));
    }

    @Operation(
            summary = "Replay a failed outbox row",
            description = "Operator. The row goes back to PENDING with attempts 0. Only a FAILED row can be replayed.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "204", description = "The row is pending again.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an operator.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such outbox id.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The row is not FAILED.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/outbox/{id}/replay")
    public ResponseEntity<Void> replay(
            @PathVariable long id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        outbox.replay(id, Callers.tenant(tenantId));
        return ResponseEntity.noContent().build();
    }

    @Operation(
            summary = "Discard a failed outbox row",
            description = "Operator. Body is {\"reason\":\"...\"}. Only a FAILED row can be discarded. Later events for that employee can publish after the discard.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "204", description = "The row is discarded.")
    @ApiResponse(responseCode = "400", description = "The reason is missing.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an operator.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such outbox id.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "The row is not FAILED.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/outbox/{id}/discard")
    public ResponseEntity<Void> discard(
            @PathVariable long id,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody DiscardRequest request
    ) {
        outbox.discard(id, Callers.tenant(tenantId), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    public record DiscardRequest(@Schema(example = "duplicate delivery") String reason) {
    }
}
