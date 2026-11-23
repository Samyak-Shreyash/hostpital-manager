package com.hms.reporting.web;

import com.hms.reporting.application.ReportResult;
import java.util.Map;
import java.util.Set;

/** RFC 4180 CSV for a report, with the spreadsheet formula-injection guard on text cells. */
final class CsvWriter {

    private static final Set<String> MONEY = Set.of(
            "amount", "collected", "refunded", "net", "total", "credited", "paid", "balance");

    private CsvWriter() {
    }

    static String write(ReportResult result) {
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", result.columns())).append("\r\n");
        for (Map<String, Object> row : result.rows()) {
            boolean first = true;
            for (String column : result.columns()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(cell(column, row.get(column)));
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    private static String cell(String column, Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        boolean numeric = value instanceof Number || MONEY.contains(column);
        if (!numeric && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
