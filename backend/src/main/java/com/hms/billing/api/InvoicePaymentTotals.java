package com.hms.billing.api;

import com.hms.shared.domain.Money;
import java.util.UUID;

/**
 * Port for what billing must print but payment owns ([E7-5]): the sums paid and refunded against an invoice. Billing
 * declares it and the payment module implements it (dependency inversion, the same pattern as the {@code ChargeSource}
 * port); billing never depends on payment. The outstanding amount is
 * {@code total - credited - paid + refunded}.
 */
public interface InvoicePaymentTotals {

    /** Sums over the invoice's payments and effective refunds; zero when there are none. Org-scoped. */
    Totals totals(UUID organizationId, UUID invoiceId);

    record Totals(Money paid, Money refunded) {
    }
}
