package com.moveinsync.hrsync.domain;

import java.util.Optional;
import java.util.regex.Pattern;

public final class EventValidator {

    static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{7,14}");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    static final int MAX_EMAIL_LENGTH = 320;

    public Optional<ReasonCode> validate(SyncEvent event, OfficeDirectory offices, FieldChannel channel) {
        if (channel == FieldChannel.TRANSPORT) {
            return validateTransport(event);
        }
        return validateHr(event, offices);
    }

    private Optional<ReasonCode> validateHr(SyncEvent event, OfficeDirectory offices) {
        if (event == null
                || event.tenantId() == null
                || event.employeeId() == null
                || event.sourceVersion() == null
                || event.sourceVersion() < 1
                || event.type() == null) {
            return Optional.of(ReasonCode.MISSING_REQUIRED_FIELD);
        }
        EmployeeFields fields = event.fieldsOrEmpty();
        return switch (event.type()) {
            case CREATE -> validateCreate(event, fields, offices);
            case UPDATE -> validateUpdate(event, fields, offices);
            case DEACTIVATE -> validatePresentContact(event, fields, offices);
        };
    }

    private Optional<ReasonCode> validateTransport(SyncEvent event) {
        if (event == null
                || event.tenantId() == null
                || event.employeeId() == null
                || event.sourceVersion() == null
                || event.sourceVersion() < 1
                || event.type() != EventType.UPDATE) {
            return Optional.of(ReasonCode.MISSING_REQUIRED_FIELD);
        }
        EmployeeFields fields = event.fieldsOrEmpty();
        if (fields.pickupPoint() == null && fields.specialAssistance() == null) {
            return Optional.of(ReasonCode.MISSING_REQUIRED_FIELD);
        }
        return Optional.empty();
    }

    private Optional<ReasonCode> validateCreate(SyncEvent event, EmployeeFields fields, OfficeDirectory offices) {
        if (fields.name() == null || fields.email() == null || fields.phone() == null || fields.officeCode() == null) {
            return Optional.of(ReasonCode.MISSING_REQUIRED_FIELD);
        }
        return validatePresentContact(event, fields, offices);
    }

    private Optional<ReasonCode> validateUpdate(SyncEvent event, EmployeeFields fields, OfficeDirectory offices) {
        if (fields.name() == null
                && fields.email() == null
                && fields.phone() == null
                && fields.manager() == null
                && fields.officeCode() == null) {
            return Optional.of(ReasonCode.MISSING_REQUIRED_FIELD);
        }
        return validatePresentContact(event, fields, offices);
    }

    private Optional<ReasonCode> validatePresentContact(SyncEvent event, EmployeeFields fields, OfficeDirectory offices) {
        if (fields.phone() != null && !E164.matcher(fields.phone()).matches()) {
            return Optional.of(ReasonCode.INVALID_PHONE);
        }
        if (fields.email() != null && !validEmail(fields.email())) {
            return Optional.of(ReasonCode.INVALID_EMAIL);
        }
        if (fields.officeCode() != null && !offices.exists(event.tenantId(), fields.officeCode())) {
            return Optional.of(ReasonCode.UNKNOWN_OFFICE);
        }
        return Optional.empty();
    }

    private static boolean validEmail(String email) {
        return email.length() <= MAX_EMAIL_LENGTH && EMAIL.matcher(email).matches();
    }
}
