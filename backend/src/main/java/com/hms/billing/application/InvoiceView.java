package com.hms.billing.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as the API returns it (api-design.md section 12). Money, quantity and tax rate are decimal strings with
 * two places ("117.99"); {@code items} is empty in list results.
 */
public record InvoiceView(UUID id, String type, UUID patientId, UUID encounterId, UUID admissionId, String status,
        String invoiceNo, Instant issuedAt, String subtotal, String discountTotal, String taxTotal, String total,
        long version, List<Item> items, Instant createdAt, Instant updatedAt) {

    public record Item(UUID id, UUID serviceId, String description, String quantity, String unitPrice,
            String discount, String discountReason, String taxRate, String gross, String taxable, String tax,
            String total) {
    }
}
