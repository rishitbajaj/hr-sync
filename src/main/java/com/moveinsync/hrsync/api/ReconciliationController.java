package com.moveinsync.hrsync.api;

import com.moveinsync.hrsync.service.ReconciliationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    public ReconciliationRunResponse start(HttpServletRequest request) throws IOException {
        return reconciliation.run(request.getInputStream());
    }

    @GetMapping("/reconciliation/runs/{id}")
    public ReconciliationRunResponse get(@PathVariable UUID id) {
        return reconciliation.get(id);
    }

    @PostMapping("/reconciliation/runs/{id}/approve")
    public ReconciliationRunResponse approve(@PathVariable UUID id) {
        return reconciliation.approve(id);
    }
}
