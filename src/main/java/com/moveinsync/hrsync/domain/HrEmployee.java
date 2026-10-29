package com.moveinsync.hrsync.domain;

import java.time.Instant;
import java.util.UUID;

public final class HrEmployee {

    private UUID id;
    private final String tenantId;
    private final String employeeId;
    private String name;
    private String email;
    private String phone;
    private String manager;
    private String officeCode;
    private String pickupPoint;
    private String specialAssistance;
    private long hrVersion;
    private EmployeeStatus status;
    private Instant updatedAt;

    private HrEmployee(String tenantId, String employeeId) {
        this.tenantId = tenantId;
        this.employeeId = employeeId;
    }

    public static HrEmployee createFrom(SyncEvent event, Instant now) {
        EmployeeFields fields = event.fieldsOrEmpty();
        HrEmployee employee = new HrEmployee(event.tenantId(), event.employeeId());
        employee.name = fields.name();
        employee.email = fields.email();
        employee.phone = fields.phone();
        employee.manager = fields.manager();
        employee.officeCode = fields.officeCode();
        employee.hrVersion = event.sourceVersion();
        employee.status = EmployeeStatus.ACTIVE;
        employee.updatedAt = now;
        return employee;
    }

    public static HrEmployee rehydrate(
            UUID id,
            String tenantId,
            String employeeId,
            String name,
            String email,
            String phone,
            String manager,
            String officeCode,
            String pickupPoint,
            String specialAssistance,
            long hrVersion,
            EmployeeStatus status,
            Instant updatedAt
    ) {
        HrEmployee employee = new HrEmployee(tenantId, employeeId);
        employee.id = id;
        employee.name = name;
        employee.email = email;
        employee.phone = phone;
        employee.manager = manager;
        employee.officeCode = officeCode;
        employee.pickupPoint = pickupPoint;
        employee.specialAssistance = specialAssistance;
        employee.hrVersion = hrVersion;
        employee.status = status;
        employee.updatedAt = updatedAt;
        return employee;
    }

    public void applyHrUpdate(SyncEvent event, Instant now) {
        EmployeeFields fields = event.fieldsOrEmpty();
        if (fields.name() != null) {
            name = fields.name();
        }
        if (fields.email() != null) {
            email = fields.email();
        }
        if (fields.phone() != null) {
            phone = fields.phone();
        }
        if (fields.manager() != null) {
            manager = fields.manager();
        }
        if (fields.officeCode() != null) {
            officeCode = fields.officeCode();
        }
        hrVersion = event.sourceVersion();
        updatedAt = now;
    }

    public void deactivate(long sourceVersion, Instant now) {
        status = EmployeeStatus.DEACTIVATED;
        hrVersion = sourceVersion;
        updatedAt = now;
    }

    public UUID id() {
        return id;
    }

    public String tenantId() {
        return tenantId;
    }

    public String employeeId() {
        return employeeId;
    }

    public String name() {
        return name;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String manager() {
        return manager;
    }

    public String officeCode() {
        return officeCode;
    }

    public String pickupPoint() {
        return pickupPoint;
    }

    public String specialAssistance() {
        return specialAssistance;
    }

    public long hrVersion() {
        return hrVersion;
    }

    public EmployeeStatus status() {
        return status;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Override
    public String toString() {
        return "HrEmployee[tenantId=%s, employeeId=%s, hrVersion=%s, status=%s]".formatted(
                tenantId, employeeId, hrVersion, status);
    }
}
