package com.watchonce.run;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal RFC-4180-ish CSV parsing for batch runs: header row = variable names, each
 * following row = one run's values. Hand-rolled rather than pulling in a dependency for
 * something this small; handles quoted fields (with embedded commas/quotes) and both
 * CRLF and LF line endings.
 */
public final class CsvUtil {

    private CsvUtil() {}

    public static List<Map<String, String>> parseRows(String csvText) {
        List<List<String>> rows = tokenize(csvText);
        if (rows.isEmpty()) return List.of();

        List<String> header = rows.get(0);
        List<Map<String, String>> out = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() == 1 && row.get(0).isBlank()) continue; // trailing blank line
            Map<String, String> values = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                values.put(header.get(c).trim(), c < row.size() ? row.get(c) : "");
            }
            out.add(values);
        }
        return out;
    }

    private static List<List<String>> tokenize(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;

        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    rowHasContent = true;
                    i++;
                }
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                    i++;
                }
                case '\r' -> i++;
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                    rowHasContent = false;
                    i++;
                }
                default -> {
                    field.append(c);
                    rowHasContent = true;
                    i++;
                }
            }
        }
        if (rowHasContent || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }
}
