package com.hms.clinical.domain;

import java.math.BigDecimal;

/**
 * The vitals an encounter records, each with hard limits (outside: rejected, 422) and an optional plausible range
 * (outside it but within the limits: accepted with a warning). Ranges are inclusive.
 */
public enum ObservationKind {
    BP_SYS(0, 300, 70, 200),
    BP_DIA(0, 200, 40, 130),
    PULSE(0, 300, 40, 180),
    TEMP_C(25, 45, 35, 41),
    SPO2(0, 100, 85, 100),
    RESP_RATE(0, 80, 8, 40),
    WEIGHT_KG(0, 500, null, null),
    HEIGHT_CM(0, 272, null, null);

    private final BigDecimal min;
    private final BigDecimal max;
    private final BigDecimal plausibleMin;
    private final BigDecimal plausibleMax;

    ObservationKind(int min, int max, Integer plausibleMin, Integer plausibleMax) {
        this.min = BigDecimal.valueOf(min);
        this.max = BigDecimal.valueOf(max);
        this.plausibleMin = plausibleMin == null ? null : BigDecimal.valueOf(plausibleMin);
        this.plausibleMax = plausibleMax == null ? null : BigDecimal.valueOf(plausibleMax);
    }

    public boolean withinLimits(BigDecimal value) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    /** Whether the value is outside the plausible range (a warning, not an error); false when there is none. */
    public boolean implausible(BigDecimal value) {
        return plausibleMin != null && (value.compareTo(plausibleMin) < 0 || value.compareTo(plausibleMax) > 0);
    }

    public String limitsText() {
        return min.toPlainString() + " to " + max.toPlainString();
    }
}
