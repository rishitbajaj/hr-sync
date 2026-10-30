package com.moveinsync.hrsync.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves the Prometheus scrape when the actuator endpoint is not registered.
 * {@code /actuator/**} is operator-only.
 */
@RestController
@ConditionalOnMissingBean(PrometheusScrapeEndpoint.class)
public class PrometheusScrapeController {

    private final PrometheusMeterRegistry registry;

    public PrometheusScrapeController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(
            path = "/actuator/prometheus",
            produces = {
                    "text/plain",
                    "text/plain;version=0.0.4;charset=utf-8",
                    "application/openmetrics-text;version=1.0.0;charset=utf-8"
            }
    )
    public String scrape() {
        return registry.scrape();
    }
}
