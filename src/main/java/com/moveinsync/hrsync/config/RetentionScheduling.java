package com.moveinsync.hrsync.config;

import com.moveinsync.hrsync.service.RetentionJob;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

@Configuration
public class RetentionScheduling implements SchedulingConfigurer {

    private final RetentionJob job;
    private final RetentionSettings settings;

    public RetentionScheduling(RetentionJob job, RetentionSettings settings) {
        this.job = job;
        this.settings = settings;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(job::run, settings.getInterval());
    }
}
