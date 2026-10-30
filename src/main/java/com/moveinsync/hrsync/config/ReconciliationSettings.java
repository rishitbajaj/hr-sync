package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hr-sync.reconciliation")
public class ReconciliationSettings {

    private int bucketCount = 256;

    private double maxMissingRatio = 0.30;

    private String operatorPassword = "change-me";

    public int getBucketCount() {
        return bucketCount;
    }

    public void setBucketCount(int bucketCount) {
        this.bucketCount = bucketCount;
    }

    public double getMaxMissingRatio() {
        return maxMissingRatio;
    }

    public void setMaxMissingRatio(double maxMissingRatio) {
        this.maxMissingRatio = maxMissingRatio;
    }

    public String getOperatorPassword() {
        return operatorPassword;
    }

    public void setOperatorPassword(String operatorPassword) {
        this.operatorPassword = operatorPassword;
    }
}
