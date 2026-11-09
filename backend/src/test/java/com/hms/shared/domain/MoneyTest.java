package com.hms.shared.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void normalisesToTwoDecimals() {
        assertThat(Money.of("10")).hasToString("10.00");
        assertThat(Money.of("10.5")).hasToString("10.50");
        assertThat(Money.of(new BigDecimal("7.1"))).hasToString("7.10");
    }

    @Test
    void neverRoundsOnConstruction() {
        assertThatThrownBy(() -> Money.of("1.005")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonDecimalText() {
        assertThatThrownBy(() -> Money.of("abc")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fitsNumeric14Comma2() {
        assertThat(Money.of("999999999999.99")).hasToString("999999999999.99");
        assertThatThrownBy(() -> Money.of("1000000000000.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multiplyRoundsHalfUpAwayFromZero() {
        assertThat(Money.of("0.01").multiply(new BigDecimal("0.5"))).hasToString("0.01");
        assertThat(Money.of("0.01").multiply(new BigDecimal("0.4"))).hasToString("0.00");
        assertThat(Money.of("-0.01").multiply(new BigDecimal("0.5"))).hasToString("-0.01");
    }

    @Test
    void multiplyForLineAmounts() {
        assertThat(Money.of("10.00").multiply(new BigDecimal("0.333"))).hasToString("3.33");
        assertThat(Money.of("33.33").multiply(new BigDecimal("3"))).hasToString("99.99");
    }

    @Test
    void percentageRoundsHalfUp() {
        assertThat(Money.of("99.99").percentage(new BigDecimal("18"))).hasToString("18.00");
        assertThat(Money.of("0.04").percentage(new BigDecimal("12"))).hasToString("0.00");
        assertThat(Money.of("0.25").percentage(new BigDecimal("18"))).hasToString("0.05");
    }

    @Test
    void totalIsSumOfRoundedLinesNotRoundedSum() {
        Money line = Money.of("0.03");
        Money tax = line.percentage(new BigDecimal("18"));
        assertThat(tax).hasToString("0.01");

        Money totalTax = tax.plus(tax).plus(tax);

        // Rounding the unrounded total (3 × 0.0054 = 0.0162) would give 0.02.
        assertThat(totalTax).isEqualTo(Money.of("0.03"));
    }

    @Test
    void addsAndSubtracts() {
        assertThat(Money.of("100.10").plus(Money.of("0.90"))).hasToString("101.00");
        Money negative = Money.of("1.00").minus(Money.of("2.50"));
        assertThat(negative).hasToString("-1.50");
        assertThat(negative.isNegative()).isTrue();
        assertThat(Money.ZERO.isZero()).isTrue();
    }

    @Test
    void equalityIgnoresInputScale() {
        assertThat(Money.of("5")).isEqualTo(Money.of("5.00")).hasSameHashCodeAs(Money.of("5.00"));
        assertThat(Money.of("5.00").compareTo(Money.of("5.01"))).isNegative();
    }
}
