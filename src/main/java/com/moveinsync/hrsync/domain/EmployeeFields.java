package com.moveinsync.hrsync.domain;

public record EmployeeFields(
        String name,
        String email,
        String phone,
        String manager,
        String officeCode,
        String pickupPoint,
        String specialAssistance,
        String shift
) {
    public EmployeeFields {
        name = Text.trimToNull(name);
        email = Text.trimToNull(email);
        phone = Text.trimToNull(phone);
        manager = Text.trimToNull(manager);
        officeCode = Text.trimToNull(officeCode);
        pickupPoint = Text.trimToNull(pickupPoint);
        specialAssistance = Text.trimToNull(specialAssistance);
        shift = Text.trimToNull(shift);
    }

    public static EmployeeFields empty() {
        return new EmployeeFields(null, null, null, null, null, null, null, null);
    }

    @Override
    public String toString() {
        return "EmployeeFields[redacted]";
    }
}
