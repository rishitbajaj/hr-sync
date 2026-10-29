package com.moveinsync.hrsync.domain;

public interface OfficeDirectory {

    boolean exists(String tenantId, String officeCode);
}
