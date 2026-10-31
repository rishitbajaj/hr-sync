package com.moveinsync.hrsync.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @Operation(summary = "Liveness", description = "Open. No token. Body is status UP when the process is up.")
    @ApiResponse(responseCode = "200", description = "The process is up.")
    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
