package com.hms.billing.domain;

import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One invoice line. {@code unitPrice} is a snapshot (catalog price at the time the line was added, or an override);
 * the rounded line amounts are computed once by {@link InvoiceCalculator} and stored with the line.
 */
public record InvoiceLine(UUID serviceId, String description, BigDecimal quantity, Money unitPrice, Money discount,
        String discountReason, BigDecimal taxRate, LineTotals totals) {

    public static InvoiceLine of(UUID serviceId, String description, BigDecimal quantity, Money unitPrice,
            Money discount, String discountReason, BigDecimal taxRate) {
        return new InvoiceLine(serviceId, description, quantity, unitPrice, discount, discountReason, taxRate,
                InvoiceCalculator.line(quantity, unitPrice, discount, taxRate));
    }
}
