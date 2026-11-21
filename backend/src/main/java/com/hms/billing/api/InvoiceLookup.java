package com.hms.billing.api;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only reference to an invoice for other modules (the payment receipt, [E7-5]): its printed number and patient.
 * An unknown or other-organization id yields empty.
 */
public interface InvoiceLookup {

    Optional<InvoiceRef> find(UUID organizationId, UUID invoiceId);

    /**
     * [E8-6] Whether the admission has a live (not DISCARDED or CANCELLED) IPD invoice. Runs in the caller's
     * transaction and takes the admission's invoicing advisory lock until it ends, so no IPD draft can be created
     * for the admission while the caller (admission cancel) decides.
     */
    boolean hasLiveIpdInvoice(UUID organizationId, UUID admissionId);

    /**
     * [E8-7] The admission's live (not DISCARDED or CANCELLED) FINAL invoice, if any. Like {@link #hasLiveIpdInvoice}
     * it runs in the caller's transaction and takes the admission's invoicing advisory lock until it ends, so no FINAL
     * can be created or discarded while the caller (discharge summary save, discharge) decides.
     */
    Optional<FinalInvoice> liveFinalInvoice(UUID organizationId, UUID admissionId);

    /** A FINAL IPD invoice: {@code issued} when ISSUED, PARTIALLY_PAID or PAID. */
    record FinalInvoice(UUID id, String status, LocalDate roomChargesThrough) {
        public boolean issued() {
            return "ISSUED".equals(status) || "PARTIALLY_PAID".equals(status) || "PAID".equals(status);
        }
    }

    /** {@code invoiceNo} is null while the invoice is still a DRAFT. */
    record InvoiceRef(UUID id, String invoiceNo, UUID patientId, String status) {
    }
}
