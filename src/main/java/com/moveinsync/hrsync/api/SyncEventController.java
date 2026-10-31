package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.config.IngestLimits;
import com.moveinsync.hrsync.config.OpenApiConfig;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SyncEventController {

    private final SyncEventService syncEventService;
    private final IngestLimits limits;

    public SyncEventController(SyncEventService syncEventService, IngestLimits limits) {
        this.syncEventService = syncEventService;
        this.limits = limits;
    }

    @Operation(
            summary = "Apply an HR batch",
            description = "Admin. Outcomes are APPLIED, REJECTED, IGNORED, and RETRYABLE_ERROR. A RETRYABLE_ERROR record makes the status 503. The 503 body is results plus correlationId. Retry-After is set. DATABASE_UNAVAILABLE was not stored. NOT_PROCESSED was not attempted. Records already APPLIED in that body stay applied. reasonCode is omitted on APPLIED.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "No record is RETRYABLE_ERROR.")
    @ApiResponse(
            responseCode = "503",
            description = "At least one record is RETRYABLE_ERROR.",
            content = @Content(schema = @Schema(implementation = SyncBatchResponse.class))
    )
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an admin, or the body tenant does not match the token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/sync/events")
    public SyncBatchResponse sync(@RequestBody SyncBatchRequest request) {
        List<SyncEventRequest> incoming = accepted(request);
        List<SyncEvent> events = incoming.stream().map(event -> toDomain(event, Callers.require().tenantId())).toList();
        return SyncBatchResponse.from(syncEventService.ingest(events, FieldChannel.HR));
    }

    @Operation(
            summary = "Apply a transport batch",
            description = "Admin. Type must be UPDATE. Transport fields are pickupPoint and specialAssistance. HR fields in the body are ignored. Outcomes match POST /sync/events, including RETRYABLE_ERROR and the 503 body.",
            security = @SecurityRequirement(name = OpenApiConfig.BEARER)
    )
    @ApiResponse(responseCode = "200", description = "No record is RETRYABLE_ERROR.")
    @ApiResponse(
            responseCode = "503",
            description = "At least one record is RETRYABLE_ERROR / DATABASE_UNAVAILABLE or NOT_PROCESSED.",
            content = @Content(schema = @Schema(implementation = SyncBatchResponse.class))
    )
    @ApiResponse(responseCode = "401", description = "Missing or invalid token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Not an admin, or the body tenant does not match the token.", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @PostMapping("/sync/transport-events")
    public SyncBatchResponse transport(@RequestBody SyncBatchRequest request) {
        List<SyncEventRequest> incoming = accepted(request);
        List<SyncEvent> events = incoming.stream().map(event -> toTransport(event, Callers.require().tenantId())).toList();
        return SyncBatchResponse.from(syncEventService.ingest(events, FieldChannel.TRANSPORT));
    }

    private List<SyncEventRequest> accepted(SyncBatchRequest request) {
        List<SyncEventRequest> incoming = request.events() == null ? List.of() : request.events();
        if (incoming.size() > limits.getMaxBatchSize()) {
            throw new BatchTooLargeException(limits.getMaxBatchSize());
        }
        for (SyncEventRequest event : incoming) {
            Callers.tenant(event == null ? null : event.tenantId());
        }
        return incoming;
    }

    private static SyncEvent toTransport(SyncEventRequest request, String tenant) {
        SyncEvent event = toDomain(request, tenant);
        if (event.type() != null) {
            return event;
        }
        return new SyncEvent(event.tenantId(), event.employeeId(), event.sourceVersion(), EventType.UPDATE, event.fields());
    }

    private static SyncEvent toDomain(SyncEventRequest request, String tenant) {
        if (request == null) {
            return new SyncEvent(tenant, null, null, null, null);
        }
        return new SyncEvent(
                tenant,
                request.employeeId(),
                request.sourceVersion(),
                EventType.parse(request.type()),
                toFields(request.fields())
        );
    }

    private static EmployeeFields toFields(SyncFieldsRequest fields) {
        if (fields == null) {
            return null;
        }
        return new EmployeeFields(
                fields.name(),
                fields.email(),
                fields.phone(),
                fields.manager(),
                fields.officeCode(),
                fields.pickupPoint(),
                fields.specialAssistance(),
                fields.shift()
        );
    }
}
