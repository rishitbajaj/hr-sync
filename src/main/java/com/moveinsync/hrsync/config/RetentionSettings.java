package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.retention")
public class RetentionSettings {

    private Duration age = Duration.ofDays(30);

    private int batchSize = 1000;

    private Duration interval = Duration.ofHours(1);

    public Duration getAge() {
        return age;
    }

    public void setAge(Duration age) {
        this.age = age;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }
}
