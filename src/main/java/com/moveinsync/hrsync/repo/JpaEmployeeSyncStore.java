package com.moveinsync.hrsync.repo;

import com.moveinsync.hrsync.domain.EmployeeSyncStore;
import com.moveinsync.hrsync.domain.HrEmployee;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class JpaEmployeeSyncStore implements EmployeeSyncStore {

    private final EmployeeRepository employees;
    private final EntityManager entityManager;

    public JpaEmployeeSyncStore(EmployeeRepository employees, EntityManager entityManager) {
        this.employees = employees;
        this.entityManager = entityManager;
    }

    @Override
    public Optional<HrEmployee> find(String tenantId, String employeeId) {
        return employees.findByTenantIdAndEmployeeId(tenantId, employeeId).map(EmployeeEntity::toDomain);
    }

    @Override
    public void save(HrEmployee employee) {
        EmployeeEntity entity = employees.findByTenantIdAndEmployeeId(employee.tenantId(), employee.employeeId())
                .orElseGet(EmployeeEntity::new);
        entity.copyFrom(employee);
        employees.save(entity);
        entityManager.flush();
    }
}
