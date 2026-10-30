package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.config.IngestLimits;
import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.FieldChannel;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.service.SyncEventService;
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

    @PostMapping("/sync/events")
    public SyncBatchResponse sync(@RequestBody SyncBatchRequest request) {
        List<SyncEventRequest> incoming = accepted(request);
        List<SyncEvent> events = incoming.stream().map(event -> toDomain(event, Callers.require().tenantId())).toList();
        return SyncBatchResponse.from(syncEventService.ingest(events, FieldChannel.HR));
    }

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
