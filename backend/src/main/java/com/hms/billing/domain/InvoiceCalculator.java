package com.hms.billing.domain;

import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Invoice arithmetic (ADR-0012): every line is rounded half-up to paise on its own, and every invoice total is the
 * plain sum of the rounded line amounts, never re-rounded.
 *
 * <pre>
 * gross   = round(quantity x unitPrice)
 * taxable = gross - discount            (0 <= discount <= gross)
 * tax     = round(taxable x taxRate / 100)
 * total   = taxable + tax
 * </pre>
 */
public final class InvoiceCalculator {

    private InvoiceCalculator() {
    }

    public record LineTotals(Money gross, Money discount, Money taxable, Money tax, Money total) {
    }

    public record InvoiceTotals(Money subtotal, Money discountTotal, Money taxTotal, Money total) {
    }

    public static LineTotals line(BigDecimal quantity, Money unitPrice, Money discount, BigDecimal taxRate) {
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(unitPrice, "unitPrice");
        Objects.requireNonNull(discount, "discount");
        Objects.requireNonNull(taxRate, "taxRate");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice.isNegative() || discount.isNegative() || taxRate.signum() < 0) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        Money gross = unitPrice.multiply(quantity);
        if (discount.compareTo(gross) > 0) {
            throw new IllegalArgumentException("discount exceeds the line amount");
        }
        Money taxable = gross.minus(discount);
        Money tax = taxable.percentage(taxRate);
        return new LineTotals(gross, discount, taxable, tax, taxable.plus(tax));
    }

    public static InvoiceTotals totals(List<LineTotals> lines) {
        Money subtotal = Money.ZERO;
        Money discounts = Money.ZERO;
        Money taxes = Money.ZERO;
        Money total = Money.ZERO;
        for (LineTotals line : lines) {
            subtotal = subtotal.plus(line.gross());
            discounts = discounts.plus(line.discount());
            taxes = taxes.plus(line.tax());
            total = total.plus(line.total());
        }
        return new InvoiceTotals(subtotal, discounts, taxes, total);
    }
}
