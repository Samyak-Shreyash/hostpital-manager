package com.hms.billing.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An invoice as the API returns it (api-design.md section 12). Money, quantity and tax rate are decimal strings with
 * two places ("117.99"); {@code items} is empty in list results. {@code kind} is INTERIM or FINAL for an IPD invoice
 * (null for OPD) and {@code roomChargesThrough} its last room-charged date ([E8-6]); an item's {@code kind} is ROOM
 * for a generated room-charge line, else STANDARD.
 */
public record InvoiceView(UUID id, String type, UUID patientId, UUID encounterId, UUID admissionId, String status,
        String invoiceNo, Instant issuedAt, String subtotal, String discountTotal, String taxTotal, String total,
        long version, List<Item> items, Instant createdAt, Instant updatedAt, String kind,
        LocalDate roomChargesThrough) {

    public record Item(UUID id, UUID serviceId, String description, String quantity, String unitPrice,
            String discount, String discountReason, String taxRate, String gross, String taxable, String tax,
            String total, String kind) {
    }
}
