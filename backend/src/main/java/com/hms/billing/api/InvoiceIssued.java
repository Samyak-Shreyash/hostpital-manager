package com.hms.billing.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}, inside the issuing transaction) when an invoice is issued
 * ([E7-2]). No listener exists yet (verified at the M3 review); any future listener with side effects must be a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back transaction never leaks the event. Ids and the business
 * number only. {@code total} is a decimal string.
 */
public record InvoiceIssued(UUID organizationId, UUID invoiceId, UUID patientId, String invoiceNo, String total,
        Instant issuedAt) {
}
