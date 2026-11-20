package com.hms.billing.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only reference to an invoice for other modules (the payment receipt, [E7-5]): its printed number and patient.
 * An unknown or other-organization id yields empty.
 */
public interface InvoiceLookup {

    Optional<InvoiceRef> find(UUID organizationId, UUID invoiceId);

    /** {@code invoiceNo} is null while the invoice is still a DRAFT. */
    record InvoiceRef(UUID id, String invoiceNo, UUID patientId, String status) {
    }
}
