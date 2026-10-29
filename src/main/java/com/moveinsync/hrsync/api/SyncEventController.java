package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.domain.EmployeeFields;
import com.moveinsync.hrsync.domain.EventType;
import com.moveinsync.hrsync.domain.SyncEvent;
import com.moveinsync.hrsync.service.SyncEventService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SyncEventController {

    private final SyncEventService syncEventService;

    public SyncEventController(SyncEventService syncEventService) {
        this.syncEventService = syncEventService;
    }

    @PostMapping("/sync/events")
    public SyncBatchResponse sync(@RequestBody SyncBatchRequest request) {
        List<SyncEventRequest> incoming = request.events() == null ? List.of() : request.events();
        List<SyncEvent> events = incoming.stream().map(SyncEventController::toDomain).toList();
        return SyncBatchResponse.from(syncEventService.ingest(events));
    }

    private static SyncEvent toDomain(SyncEventRequest request) {
        if (request == null) {
            return new SyncEvent(null, null, null, null, null);
        }
        return new SyncEvent(
                request.tenantId(),
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
                fields.specialAssistance()
        );
    }
}
