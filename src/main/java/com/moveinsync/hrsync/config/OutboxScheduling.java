package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.service.OutboxPoller;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class OutboxScheduling implements SchedulingConfigurer {

    private final OutboxPoller poller;
    private final OutboxSettings settings;

    public OutboxScheduling(OutboxPoller poller, OutboxSettings settings) {
        this.poller = poller;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(poller::poll, settings.getPollInterval());
    }
}
