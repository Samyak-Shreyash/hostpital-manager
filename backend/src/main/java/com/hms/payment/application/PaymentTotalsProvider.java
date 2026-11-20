package com.hms.payment.application;

import com.hms.billing.api.InvoicePaymentTotals;
import com.hms.payment.infrastructure.PaymentRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements billing's {@link InvoicePaymentTotals} port from the payment tables ([E7-5]): billing prints paid and
 * refunded amounts without depending on this module.
 */
@Service
class PaymentTotalsProvider implements InvoicePaymentTotals {

    private final PaymentRepository repository;

    PaymentTotalsProvider(PaymentRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Totals totals(UUID organizationId, UUID invoiceId) {
        return new Totals(repository.paidOn(organizationId, invoiceId),
                repository.refundedOn(organizationId, invoiceId));
    }
}
