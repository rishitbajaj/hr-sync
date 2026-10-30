package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.outbox")
public class OutboxSettings {

    private Duration pollInterval = Duration.ofSeconds(1);

    private int batchSize = 100;

    private int maxAttempts = 5;

    private Duration retryBase = Duration.ofMillis(500);

    private Duration retryMaxDelay = Duration.ofSeconds(30);

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getRetryBase() {
        return retryBase;
    }

    public void setRetryBase(Duration retryBase) {
        this.retryBase = retryBase;
    }

    public Duration getRetryMaxDelay() {
        return retryMaxDelay;
    }

    public void setRetryMaxDelay(Duration retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
    }
}
