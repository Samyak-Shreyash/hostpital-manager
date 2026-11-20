package com.hms.billing.domain;

import java.util.Set;

/**
 * Invoice lifecycle rules (domain-model.md section 6, ADR-0012). A DRAFT is editable and can be discarded or issued;
 * once issued the invoice is frozen and only its payment status moves (ISSUED, PARTIALLY_PAID, PAID) until it is
 * CANCELLED by a credit note. DISCARDED and CANCELLED are final. The V29 trigger enforces the same rules.
 */
public final class Invoice {

    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String DISCARDED = "DISCARDED";

    public static final Set<String> STATUSES = Set.of(DRAFT, ISSUED, PARTIALLY_PAID, PAID, CANCELLED, DISCARDED);
    /** The statuses a payment or refund may move an issued invoice between. */
    public static final Set<String> PAYMENT_STATUSES = Set.of(ISSUED, PARTIALLY_PAID, PAID);

    private Invoice() {
    }

    public static boolean isEditable(String status) {
        return DRAFT.equals(status);
    }

    public static boolean canApplyPaymentStatus(String current, String next) {
        return PAYMENT_STATUSES.contains(current) && PAYMENT_STATUSES.contains(next);
    }
}
