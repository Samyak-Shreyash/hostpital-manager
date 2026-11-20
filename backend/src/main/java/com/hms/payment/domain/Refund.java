package com.hms.payment.domain;

import com.hms.shared.domain.Money;

/**
 * Refund rules ([E7-4], ADR-0012). A refund is RECORDED when its amount is at most the approval threshold, otherwise
 * PENDING_APPROVAL until a different user approves (then RECORDED) or rejects it (REJECTED). The status is derived
 * from the append-only refund row and its at most one decision row.
 */
public final class Refund {

    public static final String RECORDED = "RECORDED";
    public static final String PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String REJECTED = "REJECTED";
    public static final String APPROVED = "APPROVED";
    public static final int MAX_REASON_LENGTH = 500;

    private Refund() {
    }

    public static String initialStatus(Money amount, Money approvalThreshold) {
        return amount.compareTo(approvalThreshold) > 0 ? PENDING_APPROVAL : RECORDED;
    }

    /** The effective status of a refund with {@code initialStatus} and {@code decision} (null when undecided). */
    public static String status(String initialStatus, String decision) {
        if (decision == null) {
            return initialStatus;
        }
        return APPROVED.equals(decision) ? RECORDED : REJECTED;
    }
}
