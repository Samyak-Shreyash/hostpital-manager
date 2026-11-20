package com.hms.billing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}, inside the issuing transaction) when an invoice is issued
 * ([E7-2]). Listeners that must run only after commit use {@code @TransactionalEventListener}. Ids and the business
 * number only. {@code total} is a decimal string.
 */
public record InvoiceIssued(UUID organizationId, UUID invoiceId, UUID patientId, String invoiceNo, String total,
        Instant issuedAt) {
}
