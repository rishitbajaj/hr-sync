package com.moveinsync.hrsync.repo;

import com.moveinsync.hrsync.domain.EmployeeStatus;
import com.moveinsync.hrsync.domain.HrEmployee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employee")
public class EmployeeEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false, length = 128)
    private String tenantId;

    @Column(name = "employee_id", nullable = false, length = 128)
    private String employeeId;

    @Column(length = 200)
    private String name;

    @Column(length = 320)
    private String email;

    @Column(length = 32)
    private String phone;

    @Column(length = 200)
    private String manager;

    @Column(name = "office_code", length = 64)
    private String officeCode;

    @Column(name = "pickup_point", length = 500)
    private String pickupPoint;

    @Column(name = "special_assistance", length = 500)
    private String specialAssistance;

    @Column(name = "hr_version", nullable = false)
    private long hrVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EmployeeStatus status;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EmployeeEntity() {
    }

    public void copyFrom(HrEmployee employee) {
        if (id == null) {
            id = employee.id() == null ? UUID.randomUUID() : employee.id();
        }
        tenantId = employee.tenantId();
        employeeId = employee.employeeId();
        name = employee.name();
        email = employee.email();
        phone = employee.phone();
        manager = employee.manager();
        officeCode = employee.officeCode();
        pickupPoint = employee.pickupPoint();
        specialAssistance = employee.specialAssistance();
        hrVersion = employee.hrVersion();
        status = employee.status();
        updatedAt = employee.updatedAt();
    }

    public HrEmployee toDomain() {
        return HrEmployee.rehydrate(
                id,
                tenantId,
                employeeId,
                name,
                email,
                phone,
                manager,
                officeCode,
                pickupPoint,
                specialAssistance,
                hrVersion,
                status,
                updatedAt
        );
    }

    public String getPhone() {
        return phone;
    }

    public String getEmail() {
        return email;
    }

    public String getPickupPoint() {
        return pickupPoint;
    }

    public EmployeeStatus getStatus() {
        return status;
    }

    public long getHrVersion() {
        return hrVersion;
    }
}
