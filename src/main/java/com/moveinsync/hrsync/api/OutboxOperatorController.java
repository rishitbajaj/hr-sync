package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.service.OutboxOperatorService;
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

    @GetMapping("/outbox/failed")
    public List<OutboxOperatorService.FailedOutboxRow> failed(
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return outbox.failed(Callers.tenant(tenantId));
    }

    @PostMapping("/outbox/{id}/replay")
    public ResponseEntity<Void> replay(
            @PathVariable long id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        outbox.replay(id, Callers.tenant(tenantId));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/outbox/{id}/discard")
    public ResponseEntity<Void> discard(
            @PathVariable long id,
            @RequestParam(name = "tenantId", required = false) String tenantId,
            @RequestBody DiscardRequest request
    ) {
        outbox.discard(id, Callers.tenant(tenantId), request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    public record DiscardRequest(String reason) {
    }
}
