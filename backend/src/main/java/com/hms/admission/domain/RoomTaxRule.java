package com.hms.admission.domain;

import com.hms.shared.domain.Money;
import java.math.BigDecimal;

/**
 * GST rate on room rent (owner decision 2026-11-21; not a legal-compliance claim). Non-critical-care rooms at or
 * below INR 5,000.00 per day are exempt; above it 5% (2.5% CGST + 2.5% SGST, no ITC). ICU, CCU, ICCU and NICU wards
 * ({@code care_type = CRITICAL_CARE}) are exempt whatever the rate. The threshold compares the per-day unit price,
 * before any discount. Applies to room-charge lines only.
 */
public final class RoomTaxRule {

    static final Money THRESHOLD = Money.of("5000.00");
    static final BigDecimal EXEMPT = new BigDecimal("0.00");
    static final BigDecimal GST_5 = new BigDecimal("5.00");

    private RoomTaxRule() {
    }

    public static BigDecimal rate(boolean criticalCare, Money dailyRate) {
        return !criticalCare && dailyRate.compareTo(THRESHOLD) > 0 ? GST_5 : EXEMPT;
    }
}
