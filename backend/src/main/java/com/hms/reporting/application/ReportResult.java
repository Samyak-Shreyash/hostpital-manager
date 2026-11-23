package com.hms.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** A generated report: ordered column names and rows keyed by them. from/to are null for snapshots. */
public record ReportResult(String report, LocalDate from, LocalDate to, Instant generatedAt, List<String> columns,
        List<Map<String, Object>> rows) {
}
