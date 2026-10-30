package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.auth.Callers;
import com.moveinsync.hrsync.service.ReconciliationService;
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

    @PostMapping(value = "/reconciliation/runs", consumes = "text/csv")
    public ReconciliationRunResponse start(
            @RequestParam(name = "tenantId", required = false) String tenantId,
            HttpServletRequest request
    ) throws IOException {
        return reconciliation.run(Callers.tenant(tenantId), request.getInputStream());
    }

    @GetMapping("/reconciliation/runs/{id}")
    public ReconciliationRunResponse get(
            @PathVariable UUID id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return reconciliation.get(id, Callers.tenant(tenantId));
    }

    @PostMapping("/reconciliation/runs/{id}/approve")
    public ReconciliationRunResponse approve(
            @PathVariable UUID id,
            @RequestParam(name = "tenantId", required = false) String tenantId
    ) {
        return reconciliation.approve(id, Callers.tenant(tenantId));
    }
}
