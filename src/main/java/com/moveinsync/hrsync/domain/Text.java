package com.moveinsync.hrsync.domain;

final class Text {

    private Text() {
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static boolean isBlank(String value) {
        return trimToNull(value) == null;
    }
}
