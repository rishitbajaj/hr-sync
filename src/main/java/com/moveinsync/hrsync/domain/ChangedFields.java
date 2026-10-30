package com.moveinsync.hrsync.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ChangedFields {

    private ChangedFields() {
    }

    public static List<String> between(HrEmployee before, HrEmployee after, FieldChannel channel) {
        List<String> changed = new ArrayList<>();
        if (channel == FieldChannel.TRANSPORT) {
            differ(changed, "pickupPoint", value(before, HrEmployee::pickupPoint), after.pickupPoint());
            differ(changed, "specialAssistance", value(before, HrEmployee::specialAssistance), after.specialAssistance());
            return List.copyOf(changed);
        }
        differ(changed, "name", value(before, HrEmployee::name), after.name());
        differ(changed, "email", value(before, HrEmployee::email), after.email());
        differ(changed, "phone", value(before, HrEmployee::phone), after.phone());
        differ(changed, "manager", value(before, HrEmployee::manager), after.manager());
        differ(changed, "officeCode", value(before, HrEmployee::officeCode), after.officeCode());
        differ(changed, "shift", value(before, HrEmployee::shift), after.shift());
        if (before == null || before.status() != after.status()) {
            changed.add("status");
        }
        return List.copyOf(changed);
    }

    private static void differ(List<String> changed, String field, String before, String after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    private static String value(HrEmployee employee, FieldReader reader) {
        return employee == null ? null : reader.read(employee);
    }

    @FunctionalInterface
    private interface FieldReader {
        String read(HrEmployee employee);
    }
}
