package com.hms.payment.application;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * The {@code 201} body of {@code POST /invoices/{id}/payments}: the payment plus the invoice's outstanding amount and
 * status right after it. Stored as the idempotent response, so a replay returns exactly this.
 */
public record RecordedPayment(UUID id, UUID invoiceId, Money amount, String mode, String referenceNo,
        String receiptNo, Instant receivedAt, UUID receivedBy, Money outstanding, String invoiceStatus) {
}
