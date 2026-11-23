package com.hms.reporting.application;

import com.hms.shared.domain.BusinessRuleException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Inclusive range of organization-local dates for a ranged report: from <= to, at most 366 days. */
public record ReportRange(LocalDate from, LocalDate to) {

    static final int MAX_DAYS = 366;

    public static ReportRange of(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw invalid("from and to are required");
        }
        if (from.isAfter(to)) {
            throw invalid("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw invalid("the range must not exceed " + MAX_DAYS + " days");
        }
        return new ReportRange(from, to);
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
