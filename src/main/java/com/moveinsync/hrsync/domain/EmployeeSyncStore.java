package com.moveinsync.hrsync.domain;

import java.util.Optional;

public interface EmployeeSyncStore {

    Optional<HrEmployee> find(String tenantId, String employeeId);

    void save(HrEmployee employee);
}
