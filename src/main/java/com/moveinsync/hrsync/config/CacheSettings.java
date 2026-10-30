package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hr-sync.cache")
public class CacheSettings {

    private int officeMaximumSize = 10_000;

    private Duration officeTtl = Duration.ofSeconds(60);

    private int employeeMaximumSize = 10_000;

    private Duration employeeTtl = Duration.ofSeconds(10);

    public int getOfficeMaximumSize() {
        return officeMaximumSize;
    }

    public void setOfficeMaximumSize(int officeMaximumSize) {
        this.officeMaximumSize = officeMaximumSize;
    }

    public Duration getOfficeTtl() {
        return officeTtl;
    }

    public void setOfficeTtl(Duration officeTtl) {
        this.officeTtl = officeTtl;
    }

    public int getEmployeeMaximumSize() {
        return employeeMaximumSize;
    }

    public void setEmployeeMaximumSize(int employeeMaximumSize) {
        this.employeeMaximumSize = employeeMaximumSize;
    }

    public Duration getEmployeeTtl() {
        return employeeTtl;
    }

    public void setEmployeeTtl(Duration employeeTtl) {
        this.employeeTtl = employeeTtl;
    }
}
