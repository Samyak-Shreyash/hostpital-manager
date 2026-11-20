package com.hms.payment.domain;

import com.hms.shared.domain.Money;
import java.util.Set;

/** Payment rules ([E7-3], ADR-0012): modes, payable invoice states and the invoice status a balance implies. */
public final class Payment {

    public static final Set<String> MODES = Set.of("CASH", "CARD", "UPI", "BANK_TRANSFER", "CHEQUE");
    public static final int MAX_REFERENCE_LENGTH = 50;

    public static final String ISSUED = "ISSUED";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAID = "PAID";

    private Payment() {
    }

    /** Only an issued invoice that is not fully paid accepts payments. */
    public static boolean payable(String invoiceStatus) {
        return ISSUED.equals(invoiceStatus) || PARTIALLY_PAID.equals(invoiceStatus);
    }

    /** {@code total - paid + refunded}. */
    public static Money outstanding(Money total, Money paid, Money refunded) {
        return total.minus(paid).plus(refunded);
    }

    /** The invoice status for a net amount received ({@code paid - refunded}) against {@code total}. */
    public static String invoiceStatus(Money total, Money paid, Money refunded) {
        Money net = paid.minus(refunded);
        if (net.compareTo(total) >= 0) {
            return PAID;
        }
        return net.compareTo(Money.ZERO) > 0 ? PARTIALLY_PAID : ISSUED;
    }
}
