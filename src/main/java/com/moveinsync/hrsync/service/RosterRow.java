package com.moveinsync.hrsync.service;

import java.util.List;

record RosterRow(
        String tenantId,
        String employeeId,
        long sourceVersion,
        String name,
        String email,
        String phone,
        String manager,
        String officeCode,
        String shift
) {
    static RosterRow from(List<String> cells) {
        String employeeId = blankToNull(cells.get(1));
        if (employeeId == null) {
            throw new ReconciliationException("Invalid reconciliation export");
        }
        String versionText = blankToNull(cells.get(2));
        if (versionText == null) {
            throw new ReconciliationException("Invalid sourceVersion for employee " + employeeId);
        }
        long version;
        try {
            version = Long.parseLong(versionText);
        } catch (NumberFormatException exception) {
            throw new ReconciliationException("Invalid sourceVersion for employee " + employeeId);
        }
        String tenantId = blankToNull(cells.get(0));
        if (tenantId == null) {
            throw new ReconciliationException("Invalid reconciliation export");
        }
        return new RosterRow(
                tenantId,
                employeeId,
                version,
                blankToNull(cells.get(3)),
                blankToNull(cells.get(4)),
                blankToNull(cells.get(5)),
                blankToNull(cells.get(6)),
                blankToNull(cells.get(7)),
                blankToNull(cells.get(8))
        );
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
