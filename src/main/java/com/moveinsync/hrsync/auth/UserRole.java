package com.moveinsync.hrsync.auth;

public enum UserRole {
    EMPLOYEE,
    ADMIN,
    OPERATOR;

    public static UserRole parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
