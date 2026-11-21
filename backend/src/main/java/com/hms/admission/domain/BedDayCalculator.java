package com.hms.admission.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BR-B6 rule [A] exactly as ruling R2 of the M4 plan (pending pilot sign-off, review-notes Q1). With {@code A} the
 * admission date and {@code E} the end date in the organization's time zone, the charged dates are {@code A .. E-1};
 * a same-day stay charges {@code A} once. Date {@code D} is charged to the bed whose stay covers 00:00 of
 * {@code D+1} (a transfer at exactly midnight belongs to the new bed); a same-day stay to the bed held at the end.
 * Pure: no I/O.
 */
public final class BedDayCalculator {

    /** One bed assignment; {@code to} is null while open. Covers {@code from <= t < to}. */
    public record Stay(UUID bedId, Instant from, Instant to) {
    }

    public record BedDay(LocalDate date, UUID bedId) {
    }

    private BedDayCalculator() {
    }

    public static List<BedDay> chargedDays(List<Stay> stays, Instant admittedAt, Instant endAt, ZoneId zone) {
        LocalDate first = LocalDate.ofInstant(admittedAt, zone);
        LocalDate end = LocalDate.ofInstant(endAt, zone);
        List<BedDay> out = new ArrayList<>();
        if (!end.isAfter(first)) {
            out.add(new BedDay(first, bedAt(stays, endAt)));
            return out;
        }
        for (LocalDate d = first; d.isBefore(end); d = d.plusDays(1)) {
            out.add(new BedDay(d, bedAt(stays, d.plusDays(1).atStartOfDay(zone).toInstant())));
        }
        return out;
    }

    /** The bed whose stay covers {@code t}; else the stay released exactly at {@code t} (discharge at that instant). */
    private static UUID bedAt(List<Stay> stays, Instant t) {
        for (Stay s : stays) {
            if (!s.from().isAfter(t) && (s.to() == null || t.isBefore(s.to()))) {
                return s.bedId();
            }
        }
        for (Stay s : stays) {
            if (t.equals(s.to())) {
                return s.bedId();
            }
        }
        throw new IllegalStateException("no bed held at " + t);
    }
}
