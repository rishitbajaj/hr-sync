package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.metrics")
public class MetricsSettings {

    private Duration sampleInterval = Duration.ofSeconds(10);

    public Duration getSampleInterval() {
        return sampleInterval;
    }

    public void setSampleInterval(Duration sampleInterval) {
        this.sampleInterval = sampleInterval;
    }
}
