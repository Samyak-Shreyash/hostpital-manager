package com.hms.shared.numbering;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Indian financial year (April to March) labels used in document numbers: {@code 2026-27}. */
public final class FinancialYear {

    private FinancialYear() {
    }

    /** {@code 2026-27} for any date from 1 April 2026 to 31 March 2027. */
    public static String of(LocalDate date) {
        int start = date.getMonthValue() >= 4 ? date.getYear() : date.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** The financial year of {@code instant} in the organization's time zone {@code zone}. */
    public static String of(Instant instant, ZoneId zone) {
        return of(LocalDate.ofInstant(instant, zone));
    }
}
