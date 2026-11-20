package com.hms.billing.application;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A credit note just issued ([E7-4]); {@code creditedTotal} is the invoice's sum of credit notes including it. */
public record CreditNoteView(UUID id, UUID invoiceId, Money amount, String creditNoteNo, Instant issuedAt,
        UUID issuedBy, Money creditedTotal, String invoiceStatus) {
}
