package com.hms.payment.application;

import com.hms.payment.infrastructure.PaymentRepository.PaymentRow;
import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A recorded payment as listed by {@code GET /payments}; money is a decimal string. */
public record PaymentView(UUID id, UUID invoiceId, Money amount, String mode, String referenceNo, String receiptNo,
        Instant receivedAt, UUID receivedBy) {

    static PaymentView of(PaymentRow r) {
        return new PaymentView(r.id(), r.invoiceId(), r.amount(), r.mode(), r.referenceNo(), r.receiptNo(),
                r.receivedAt(), r.receivedBy());
    }
}
