package com.hms.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.billing.domain.InvoiceCalculator.InvoiceTotals;
import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** [E7-1] Line and invoice arithmetic (ADR-0012): HALF_UP per line, totals are sums of rounded lines. */
class InvoiceCalculatorTest {

    @Test
    void threeTimes3333At18Percent() {
        LineTotals line = InvoiceCalculator.line(new BigDecimal("3"), Money.of("33.33"), Money.ZERO,
                new BigDecimal("18"));
        assertThat(line.gross()).isEqualTo(Money.of("99.99"));
        assertThat(line.discount()).isEqualTo(Money.ZERO);
        assertThat(line.taxable()).isEqualTo(Money.of("99.99"));
        assertThat(line.tax()).isEqualTo(Money.of("18.00"));
        assertThat(line.total()).isEqualTo(Money.of("117.99"));
    }

    @Test
    void halfQuantityOfOnePaisaRoundsHalfUp() {
        LineTotals line = InvoiceCalculator.line(new BigDecimal("0.5"), Money.of("0.01"), Money.ZERO,
                BigDecimal.ZERO);
        assertThat(line.gross()).isEqualTo(Money.of("0.01"));
        assertThat(line.total()).isEqualTo(Money.of("0.01"));
    }

    @Test
    void discountReducesTheTaxableAmount() {
        LineTotals line = InvoiceCalculator.line(new BigDecimal("2"), Money.of("250.00"), Money.of("100.00"),
                new BigDecimal("5"));
        assertThat(line.gross()).isEqualTo(Money.of("500.00"));
        assertThat(line.taxable()).isEqualTo(Money.of("400.00"));
        assertThat(line.tax()).isEqualTo(Money.of("20.00"));
        assertThat(line.total()).isEqualTo(Money.of("420.00"));
    }

    @Test
    void discountAboveGrossIsRejected() {
        assertThatThrownBy(() -> InvoiceCalculator.line(BigDecimal.ONE, Money.of("10.00"), Money.of("10.01"),
                BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyInvoiceIsZero() {
        InvoiceTotals totals = InvoiceCalculator.totals(List.of());
        assertThat(totals.subtotal()).isEqualTo(Money.ZERO);
        assertThat(totals.total()).isEqualTo(Money.ZERO);
    }

    @Test
    void thousandRandomLinesTotalIsTheSumOfRoundedLines() {
        Random random = new Random(20261120L);
        List<LineTotals> lines = new ArrayList<>();
        Money subtotal = Money.ZERO;
        Money discounts = Money.ZERO;
        Money taxes = Money.ZERO;
        Money total = Money.ZERO;
        for (int i = 0; i < 1000; i++) {
            BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(999_900), 2); // 0.01 .. 9999.00
            Money unitPrice = Money.of(BigDecimal.valueOf(random.nextInt(1_000_000), 2)); // 0.00 .. 9999.99
            BigDecimal taxRate = BigDecimal.valueOf(random.nextInt(10_001), 2); // 0.00 .. 100.00
            Money gross = unitPrice.multiply(quantity);
            Money discount = Money.of(gross.amount().multiply(BigDecimal.valueOf(random.nextInt(101)))
                    .divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.DOWN));
            LineTotals line = InvoiceCalculator.line(quantity, unitPrice, discount, taxRate);
            for (Money m : List.of(line.gross(), line.discount(), line.taxable(), line.tax(), line.total())) {
                assertThat(m.amount().scale()).isEqualTo(2);
                assertThat(m.isNegative()).isFalse();
            }
            assertThat(line.total()).isEqualTo(line.taxable().plus(line.tax()));
            assertThat(line.taxable()).isEqualTo(line.gross().minus(line.discount()));
            lines.add(line);
            subtotal = subtotal.plus(line.gross());
            discounts = discounts.plus(line.discount());
            taxes = taxes.plus(line.tax());
            total = total.plus(line.total());
        }
        InvoiceTotals totals = InvoiceCalculator.totals(lines);
        assertThat(totals.subtotal()).isEqualTo(subtotal);
        assertThat(totals.discountTotal()).isEqualTo(discounts);
        assertThat(totals.taxTotal()).isEqualTo(taxes);
        assertThat(totals.total()).isEqualTo(total);
        assertThat(totals.total()).isEqualTo(totals.subtotal().minus(totals.discountTotal()).plus(totals.taxTotal()));
        assertThat(totals.total().amount().scale()).isEqualTo(2);
    }
}
