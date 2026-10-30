package com.moveinsync.hrsync.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hr-sync.bootstrap")
public class BootstrapSettings {

    private String operatorUsername;
    private String operatorPassword;
    private String adminUsername;
    private String adminPassword;
    private String adminTenant;

    public String getOperatorUsername() {
        return operatorUsername;
    }

    public void setOperatorUsername(String operatorUsername) {
        this.operatorUsername = operatorUsername;
    }

    public String getOperatorPassword() {
        return operatorPassword;
    }

    public void setOperatorPassword(String operatorPassword) {
        this.operatorPassword = operatorPassword;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername;
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword;
    }

    public String getAdminTenant() {
        return adminTenant;
    }

    public void setAdminTenant(String adminTenant) {
        this.adminTenant = adminTenant;
    }

    public boolean anyValueSet() {
        return present(operatorUsername)
                || present(operatorPassword)
                || present(adminUsername)
                || present(adminPassword)
                || present(adminTenant);
    }

    public void requireComplete() {
        StringBuilder missing = new StringBuilder();
        require(missing, "HR_SYNC_BOOTSTRAP_OPERATOR_USERNAME", operatorUsername);
        require(missing, "HR_SYNC_BOOTSTRAP_OPERATOR_PASSWORD", operatorPassword);
        require(missing, "HR_SYNC_BOOTSTRAP_ADMIN_USERNAME", adminUsername);
        require(missing, "HR_SYNC_BOOTSTRAP_ADMIN_PASSWORD", adminPassword);
        require(missing, "HR_SYNC_BOOTSTRAP_ADMIN_TENANT", adminTenant);
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Bootstrap users were requested but these variables are missing:" + missing
                            + ". Passwords have no default."
            );
        }
    }

    @Override
    public String toString() {
        return "BootstrapSettings[operatorUsername=" + operatorUsername
                + ", adminUsername=" + adminUsername
                + ", adminTenant=" + adminTenant + "]";
    }

    private static void require(StringBuilder missing, String name, String value) {
        if (!present(value)) {
            missing.append(' ').append(name);
        }
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
