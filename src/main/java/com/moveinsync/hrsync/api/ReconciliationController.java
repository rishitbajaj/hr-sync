package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.config.OpenApiConfig;
import com.moveinsync.hrsync.service.ReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.UUID;

@RestController
public class ReconciliationController {

    private final ReconciliationService reconciliation;

    public ReconciliationController(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Operation(
            summary = "Start a reconciliation",
            description = "Admin. Body is text/csv. A repeated employee id or a row for another tenant is 400 and writes nothing. A second RUNNING run for the tenant is 409 CONFLICT. A missing-ratio halt is 200 with status HALTED.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "The run was stored. Status is one of RUNNING, COMPLETED, HALTED, or FAILED.")
    @ApiResponse(responseCode = "400", description = "The file was rejected.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an admin.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "A run is already RUNNING for this tenant.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping(value = "/reconciliation/runs", consumes = "text/csv")
    public ReconciliationRunResponse start(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            HttpServletRequest request
    ) throws IOException {
        return reconciliation.run(Callers.tenant(tenantId), request.getInputStream());
    }

    @Operation(
            summary = "Read a reconciliation run",
            description = "Admin or operator.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "The run.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an admin or operator.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such run.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @GetMapping("/reconciliation/runs/{id}")
    public ReconciliationRunResponse get(
            @PathVariable UUID id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return reconciliation.get(id, Callers.tenant(tenantId));
    }

    @Operation(
            summary = "Approve a halted reconciliation",
            description = "Operator. Applies the halted deactivation set. A superseded halt is 409 SUPERSEDED and deactivates nobody.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "The halt was approved.")
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an operator.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "No such run.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "SUPERSEDED or the run is not HALTED.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/reconciliation/runs/{id}/approve")
    public ReconciliationRunResponse approve(
            @PathVariable UUID id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return reconciliation.approve(id, Callers.tenant(tenantId));
    }
}
