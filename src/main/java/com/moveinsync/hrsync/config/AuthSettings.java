package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.auth")
public class AuthSettings {

    private String jwtSecret;

    private Duration tokenTtl = Duration.ofMinutes(15);

    private int maxLoginFailures = 5;

    private Duration loginLockout = Duration.ofMinutes(15);

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public Duration getTokenTtl() {
        return tokenTtl;
    }

    public void setTokenTtl(Duration tokenTtl) {
        this.tokenTtl = tokenTtl;
    }

    public int getMaxLoginFailures() {
        return maxLoginFailures;
    }

    public void setMaxLoginFailures(int maxLoginFailures) {
        this.maxLoginFailures = maxLoginFailures;
    }

    public Duration getLoginLockout() {
        return loginLockout;
    }

    public void setLoginLockout(Duration loginLockout) {
        this.loginLockout = loginLockout;
    }
}
