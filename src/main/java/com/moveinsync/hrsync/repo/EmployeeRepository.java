package com.moveinsync.hrsync.repo;

import com.moveinsync.hrsync.domain.EmployeeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeRepository extends JpaRepository<EmployeeEntity, UUID> {

    Optional<EmployeeEntity> findByTenantIdAndEmployeeId(String tenantId, String employeeId);

    long countByTenantIdAndStatus(String tenantId, EmployeeStatus status);

    @Query("""
            select employee from EmployeeEntity employee
            where employee.tenantId = :tenantId
              and employee.employeeId = :employeeId
              and employee.status <> com.moveinsync.hrsync.domain.EmployeeStatus.PLACEHOLDER
            """)
    Optional<EmployeeEntity> findVisibleByTenantIdAndEmployeeId(
            @Param("tenantId") String tenantId,
            @Param("employeeId") String employeeId
    );
}
