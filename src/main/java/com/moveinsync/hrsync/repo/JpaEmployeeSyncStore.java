package com.moveinsync.hrsync.repo;

import com.moveinsync.hrsync.domain.EmployeeSyncStore;
import com.moveinsync.hrsync.domain.HrEmployee;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
public class JpaEmployeeSyncStore implements EmployeeSyncStore {

    private final EmployeeRepository employees;

    public JpaEmployeeSyncStore(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HrEmployee> find(String tenantId, String employeeId) {
        return employees.findByTenantIdAndEmployeeId(tenantId, employeeId).map(EmployeeEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(HrEmployee employee) {
        EmployeeEntity entity = employees.findByTenantIdAndEmployeeId(employee.tenantId(), employee.employeeId())
                .orElseGet(EmployeeEntity::new);
        entity.copyFrom(employee);
        employees.save(entity);
    }
}
