package com.hms.billing.api;

import com.hms.shared.domain.Money;
import java.util.UUID;

/**
 * What the payment module needs from an invoice ([E7-1] for [E8-x]); billing never depends on payment
 * (domain-model.md section 2). Both methods must run inside the caller's transaction. Unknown or other-organization
 * ids raise {@code NotFoundException}.
 */
public interface InvoiceBalancePort {

    /**
     * Locks the invoice row ({@code SELECT ... FOR UPDATE}, lock order bed -> admission -> invoice) until the caller's
     * transaction ends, and returns its status and total. The amounts paid and refunded are not part of the balance:
     * the payment module owns them and derives them from its own tables under this lock ([E7-3], ADR-0012).
     */
    InvoiceBalance lockForPayment(UUID organizationId, UUID invoiceId);

    /**
     * Moves an issued invoice between {@code ISSUED}, {@code PARTIALLY_PAID} and {@code PAID}; any other transition
     * raises {@code ConflictException(INVALID_STATE_TRANSITION)}. Call after {@link #lockForPayment}.
     */
    void applyPaymentStatus(UUID organizationId, UUID invoiceId, String newStatus);

    record InvoiceBalance(String status, Money total) {
    }
}
