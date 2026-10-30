package com.moveinsync.hrsync.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ExportCsv {

    private ExportCsv() {
    }

    public static void parse(InputStream input, RowConsumer consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            boolean headerChecked = false;
            List<String> row;
            while ((row = readRow(reader)) != null) {
                if (row.isEmpty()) {
                    continue;
                }
                if (!headerChecked) {
                    headerChecked = true;
                    if (isHeader(row)) {
                        continue;
                    }
                }
                if (row.size() != 9) {
                    throw new ReconciliationException("Invalid reconciliation export");
                }
                consumer.accept(RosterRow.from(row));
            }
        }
    }

    private static boolean isHeader(List<String> row) {
        return row.size() >= 2
                && "tenantId".equals(row.get(0))
                && "employeeId".equals(row.get(1));
    }

    private static List<String> readRow(BufferedReader reader) throws IOException {
        int first = reader.read();
        if (first < 0) {
            return null;
        }
        StringBuilder field = new StringBuilder();
        List<String> cells = new ArrayList<>();
        boolean quoted = false;
        int current = first;
        while (true) {
            if (current < 0) {
                cells.add(field.toString());
                return cells;
            }
            char ch = (char) current;
            if (quoted) {
                if (ch == '"') {
                    reader.mark(1);
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next >= 0) {
                            reader.reset();
                        }
                    }
                } else {
                    field.append(ch);
                }
            } else if (ch == '"' && field.isEmpty()) {
                quoted = true;
            } else if (ch == ',') {
                cells.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n') {
                cells.add(field.toString());
                return cells;
            } else if (ch != '\r') {
                field.append(ch);
            }
            current = reader.read();
        }
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(RosterRow row);
    }
}
