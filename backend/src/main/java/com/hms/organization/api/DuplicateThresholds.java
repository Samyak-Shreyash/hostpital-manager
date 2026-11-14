package com.hms.organization.api;

import java.math.BigDecimal;

/**
 * Patient duplicate-detection thresholds of an organization (BR-P3, owner decision 2026-11-13): a registration is a
 * suspected duplicate of a patient with the same date of birth and name similarity {@code >= dob}, or the same
 * normalized phone and similarity {@code >= phone}. Both are in (0, 1].
 */
public record DuplicateThresholds(BigDecimal dob, BigDecimal phone) {

    public static final BigDecimal DEFAULT_DOB = new BigDecimal("0.45");
    public static final BigDecimal DEFAULT_PHONE = new BigDecimal("0.30");

    /** In (0, 1] with at most 2 decimals (the column is numeric(3,2)). */
    public static boolean isValid(BigDecimal value) {
        return value != null && value.signum() > 0 && value.compareTo(BigDecimal.ONE) <= 0
                && value.stripTrailingZeros().scale() <= 2;
    }
}
