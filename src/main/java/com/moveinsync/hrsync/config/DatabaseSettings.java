package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.database")
public class DatabaseSettings {

    private Duration connectionTimeout = Duration.ofSeconds(3);

    private Duration statementTimeout = Duration.ofSeconds(3);

    private Duration retryAfter = Duration.ofSeconds(3);

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public Duration getStatementTimeout() {
        return statementTimeout;
    }

    public void setStatementTimeout(Duration statementTimeout) {
        this.statementTimeout = statementTimeout;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    public void setRetryAfter(Duration retryAfter) {
        this.retryAfter = retryAfter;
    }
}
