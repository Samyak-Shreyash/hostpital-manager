package com.hms.billing.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Nightly reconciliation rules ([E7-7]). Compares an invoice's stored status with the money recorded against it
 * and reports only states the application can never legitimately produce. Pure and free of I/O.
 *
 * <p>The expected-status derivation mirrors {@code payment.domain.Payment.invoiceStatus} (billing may not import
 * payment code): net received = paid - effective refunds, compared with {@code total - credit notes}. Known states
 * the application does produce, and which are therefore not flagged:
 * <ul>
 *   <li>a credit note on a PARTIALLY_PAID or PAID invoice does not change its status, so a PARTIALLY_PAID invoice
 *       whose credited remainder is already covered (expected PAID, credited &gt; 0) is accepted;</li>
 *   <li>a fully credited PAID invoice stays PAID with net paid above {@code total - credited}; a refund is the
 *       remedy, so only net paid above the invoice <em>total</em> is an overpayment;</li>
 *   <li>CANCELLED = credit notes equal the total with nothing net paid.</li>
 * </ul>
 */
public final class Reconciliation {

    /** Field names reported in the audit event (names only, never values). */
    public static final String STATUS = "status";
    public static final String NET_PAID = "netPaid";
    public static final String CREDITED_TOTAL = "creditedTotal";

    private Reconciliation() {
    }

    /** One invoice with its derived figures (see the {@code hms.invoice_reconciliation_v} view). */
    public record Row(UUID invoiceId, String status, BigDecimal total, BigDecimal credited, BigDecimal paid,
            BigDecimal refunded) {
    }

    /** The field names that disagree for {@code row}; empty when the invoice is consistent. */
    public static List<String> mismatches(Row row) {
        String status = row.status();
        if (Invoice.DRAFT.equals(status) || Invoice.DISCARDED.equals(status)) {
            return List.of(); // not issued: out of scope
        }
        List<String> fields = new ArrayList<>();
        BigDecimal net = row.paid().subtract(row.refunded());
        if (row.credited().compareTo(row.total()) > 0) {
            fields.add(CREDITED_TOTAL);
        }
        if (net.signum() < 0 || net.compareTo(row.total()) > 0) {
            fields.add(NET_PAID);
        }
        if (!statusConsistent(row, net)) {
            fields.add(STATUS);
        }
        return fields;
    }

    private static boolean statusConsistent(Row row, BigDecimal net) {
        String status = row.status();
        BigDecimal payable = row.total().subtract(row.credited());
        if (Invoice.CANCELLED.equals(status)) {
            return payable.signum() == 0 && net.signum() == 0;
        }
        if (!Invoice.PAYMENT_STATUSES.contains(status)) {
            return false;
        }
        String expected = expectedStatus(payable, net);
        if (status.equals(expected)) {
            return true;
        }
        // Credit note on a part-paid invoice: the status is not recomputed until the next payment or refund.
        return Invoice.PARTIALLY_PAID.equals(status) && Invoice.PAID.equals(expected)
                && row.credited().signum() > 0;
    }

    private static String expectedStatus(BigDecimal payable, BigDecimal net) {
        if (net.compareTo(payable) >= 0) {
            return Invoice.PAID;
        }
        return net.signum() > 0 ? Invoice.PARTIALLY_PAID : Invoice.ISSUED;
    }
}
