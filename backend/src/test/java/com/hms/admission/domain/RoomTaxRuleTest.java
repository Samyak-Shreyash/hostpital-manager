package com.hms.admission.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Owner decision 2026-11-21: GST on room rent. */
class RoomTaxRuleTest {

    @Test
    void generalWardUpToThresholdIsExempt() {
        assertThat(RoomTaxRule.rate(false, Money.of("5000.00"))).isEqualByComparingTo("0.00");
        assertThat(RoomTaxRule.rate(false, Money.of("800.00")).scale()).isEqualTo(2);
    }

    @Test
    void generalWardAboveThresholdIsFivePercent() {
        assertThat(RoomTaxRule.rate(false, Money.of("5000.01"))).isEqualTo(new BigDecimal("5.00"));
        assertThat(RoomTaxRule.rate(false, Money.of("7500.00"))).isEqualTo(new BigDecimal("5.00"));
    }

    @Test
    void criticalCareIsExemptWhateverTheRate() {
        assertThat(RoomTaxRule.rate(true, Money.of("9000.00"))).isEqualByComparingTo("0.00");
    }
}
