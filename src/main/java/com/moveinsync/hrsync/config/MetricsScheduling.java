package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.metrics.OutboxMetrics;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class MetricsScheduling implements SchedulingConfigurer {

    private final OutboxMetrics outboxMetrics;
    private final MetricsSettings settings;

    public MetricsScheduling(OutboxMetrics outboxMetrics, MetricsSettings settings) {
        this.outboxMetrics = outboxMetrics;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(outboxMetrics::sample, settings.getSampleInterval());
    }
}
