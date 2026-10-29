package com.moveinsync.hrsync.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<EmployeeEntity, UUID> {

    Optional<EmployeeEntity> findByTenantIdAndEmployeeId(String tenantId, String employeeId);
}
