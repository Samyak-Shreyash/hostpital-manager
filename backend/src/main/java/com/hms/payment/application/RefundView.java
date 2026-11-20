package com.hms.payment.application;

import com.hms.payment.domain.Refund;
import com.hms.payment.infrastructure.RefundRepository.RefundState;
import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A refund and its derived status ([E7-4]); the free-text reason is not echoed. */
public record RefundView(UUID id, UUID paymentId, UUID invoiceId, Money amount, String mode, String refundNo,
        String status, Instant requestedAt, UUID requestedBy, Instant decidedAt, UUID decidedBy,
        String invoiceStatus) {

    static RefundView of(RefundState s, UUID invoiceId, String invoiceStatus) {
        var r = s.refund();
        return new RefundView(r.id(), r.paymentId(), invoiceId, r.amount(), r.mode(), r.refundNo(),
                Refund.status(r.initialStatus(), s.decision()), r.requestedAt(), r.requestedBy(), s.decidedAt(),
                s.decidedBy(), invoiceStatus);
    }
}
