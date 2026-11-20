package com.hms.payment.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.InvoiceBalancePort;
import com.hms.billing.api.InvoiceBalancePort.InvoiceBalance;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.payment.domain.Payment;
import com.hms.payment.domain.Refund;
import com.hms.payment.infrastructure.PaymentRepository;
import com.hms.payment.infrastructure.PaymentRepository.PaymentRow;
import com.hms.payment.infrastructure.RefundRepository;
import com.hms.payment.infrastructure.RefundRepository.RefundRow;
import com.hms.payment.infrastructure.RefundRepository.RefundState;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.numbering.FinancialYear;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.ProblemException;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refunds with approval ([E7-4], ADR-0012, plan ruling R4). Creating one runs in one transaction: the
 * Idempotency-Key advisory lock, the payment lookup (404), the invoice row lock through billing's
 * {@link InvoiceBalancePort} (so concurrent refunds and payments on one invoice serialize), the check
 * {@code amount <= payment - non-rejected refunds} (else 422 REFUND_EXCEEDS_PAID), then the REFUND number last.
 * Amounts up to {@code hms.billing.refund-approval-threshold} (default 1000.00) are RECORDED at once; larger ones
 * wait in PENDING_APPROVAL for a different user with {@code refund:approve} (the requester gets 403
 * SELF_APPROVAL_FORBIDDEN). Only effective refunds (RECORDED, or approved) move the invoice status. Audit records
 * field names only, never the reason text.
 */
@Service
public class RefundService {

    static final String CREATE_ENDPOINT = "POST /api/v1/payments/{id}/refunds";
    static final String SEQUENCE = "REFUND";
    static final String ENTITY = "REFUND";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final InvoiceBalancePort invoices;
    private final OrganizationDirectory organizations;
    private final DocumentNumberAllocator numbers;
    private final IdempotencyService idempotency;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final Money approvalThreshold;

    public RefundService(RefundRepository refunds, PaymentRepository payments, InvoiceBalancePort invoices,
            OrganizationDirectory organizations, DocumentNumberAllocator numbers, IdempotencyService idempotency,
            AuditRecorder audit, UuidV7Generator ids, Clock clock,
            @Value("${hms.billing.refund-approval-threshold:1000.00}") String approvalThreshold) {
        this.refunds = refunds;
        this.payments = payments;
        this.invoices = invoices;
        this.organizations = organizations;
        this.numbers = numbers;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.approvalThreshold = Money.of(approvalThreshold);
    }

    /** The request hash of a refund: route, payment id and the canonical body fields in order. */
    public static String createRequestHash(UUID paymentId, Money amount, String mode, String reason) {
        return IdempotencyService.hash(CREATE_ENDPOINT, paymentId, amount == null ? null : amount.toString(), mode,
                reason);
    }

    @Transactional
    public IdempotencyService.Result<RefundView> create(OrganizationPrincipal actor, UUID paymentId, Money amount,
            String mode, String reason, String idempotencyKey, String requestHash) {
        IdempotencyService.requireKey(idempotencyKey);
        validate(amount, mode, reason);
        return idempotency.execute(actor.organizationId(), CREATE_ENDPOINT, idempotencyKey, requestHash, 201,
                RefundView.class, () -> doCreate(actor, paymentId, amount, mode, reason.strip()));
    }

    private RefundView doCreate(OrganizationPrincipal actor, UUID paymentId, Money amount, String mode,
            String reason) {
        UUID org = actor.organizationId();
        PaymentRow payment = payments.find(org, paymentId).orElseThrow(RefundService::paymentNotFound);
        InvoiceBalance balance = invoices.lockForPayment(org, payment.invoiceId()); // first lock: the invoice row
        Money refundable = payment.amount().minus(refunds.reservedOn(org, paymentId));
        if (amount.compareTo(refundable) > 0) {
            throw new BusinessRuleException("REFUND_EXCEEDS_PAID", "Refundable is " + refundable + ".");
        }
        String initialStatus = Refund.initialStatus(amount, approvalThreshold);
        Instant now = clock.instant();
        ZoneId zone = organizations.find(org).map(OrganizationSummary::timezone).map(ZoneId::of)
                .orElseThrow(() -> new IllegalStateException("organization without a time zone"));
        String fy = FinancialYear.of(now, zone);
        long sequence = numbers.next(org, SEQUENCE, "FY" + fy); // last lock: after the invoice row
        String refundNo = "RF-" + fy + "/" + String.format("%06d", sequence);
        RefundRow row = new RefundRow(ids.next(), paymentId, amount, mode, reason, refundNo, initialStatus, now,
                actor.userId());
        refunds.insert(org, row);
        List<String> fields = new ArrayList<>(List.of("paymentId", "amount", "mode", "reason", "refundNo", "status",
                "requestedAt", "requestedBy"));
        String invoiceStatus = balance.status();
        if (Refund.RECORDED.equals(initialStatus)) {
            invoiceStatus = applyInvoiceStatus(org, payment.invoiceId(), balance, fields);
        }
        audit.record(new AuditEntry(org, actor.userId(), "REFUND_REQUESTED", ENTITY, row.id(), AuditOutcome.SUCCESS,
                fields));
        return RefundView.of(new RefundState(row, null, null, null), payment.invoiceId(), invoiceStatus);
    }

    @Transactional
    public RefundView approve(OrganizationPrincipal actor, UUID refundId) {
        return decide(actor, refundId, Refund.APPROVED, null);
    }

    @Transactional
    public RefundView reject(OrganizationPrincipal actor, UUID refundId, String reason) {
        if (reason == null || reason.isBlank() || reason.strip().length() > Refund.MAX_REASON_LENGTH) {
            throw invalid(List.of(error("reason", "SIZE",
                    "reason must be 1 to " + Refund.MAX_REASON_LENGTH + " characters.")));
        }
        return decide(actor, refundId, Refund.REJECTED, reason.strip());
    }

    private RefundView decide(OrganizationPrincipal actor, UUID refundId, String decision, String reason) {
        UUID org = actor.organizationId();
        RefundState before = refunds.find(org, refundId)
                .orElseThrow(() -> new NotFoundException("Refund not found."));
        PaymentRow payment = payments.find(org, before.refund().paymentId()).orElseThrow();
        InvoiceBalance balance = invoices.lockForPayment(org, payment.invoiceId()); // serializes decisions
        RefundState locked = refunds.find(org, refundId).orElseThrow();
        if (!Refund.PENDING_APPROVAL.equals(Refund.status(locked.refund().initialStatus(), locked.decision()))) {
            throw new ConflictException("INVALID_STATE_TRANSITION", "Only a PENDING_APPROVAL refund can be decided.");
        }
        if (actor.userId().equals(locked.refund().requestedBy())) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "A refund must be decided by a user other than its requester.");
        }
        Instant now = clock.instant();
        refunds.insertDecision(org, ids.next(), refundId, decision, reason, now, actor.userId());
        List<String> fields = new ArrayList<>(List.of("status", "decidedAt", "decidedBy"));
        if (reason != null) {
            fields.add("reason");
        }
        String invoiceStatus = balance.status();
        if (Refund.APPROVED.equals(decision)) {
            invoiceStatus = applyInvoiceStatus(org, payment.invoiceId(), balance, fields);
        }
        audit.record(new AuditEntry(org, actor.userId(),
                Refund.APPROVED.equals(decision) ? "REFUND_APPROVED" : "REFUND_REJECTED", ENTITY, refundId,
                AuditOutcome.SUCCESS, fields));
        return RefundView.of(new RefundState(locked.refund(), decision, now, actor.userId()), payment.invoiceId(),
                invoiceStatus);
    }

    /** Recomputes the invoice status from the effective payments and refunds (under the invoice lock). */
    private String applyInvoiceStatus(UUID org, UUID invoiceId, InvoiceBalance balance, List<String> fields) {
        String status = Payment.invoiceStatus(balance.payable(), payments.paidOn(org, invoiceId),
                payments.refundedOn(org, invoiceId));
        invoices.applyPaymentStatus(org, invoiceId, status);
        if (!status.equals(balance.status())) {
            fields.add("invoice.status");
        }
        return status;
    }

    private static void validate(Money amount, String mode, String reason) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (amount == null) {
            errors.add(error("amount", "REQUIRED", "amount is required."));
        } else if (amount.compareTo(Money.ZERO) <= 0 || amount.amount().compareTo(MAX_AMOUNT) > 0) {
            errors.add(error("amount", "RANGE", "amount must be greater than 0.00."));
        }
        if (mode == null || !Payment.MODES.contains(mode)) {
            errors.add(error("mode", "INVALID", "mode must be one of " + String.join(", ",
                    Payment.MODES.stream().sorted().toList()) + "."));
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > Refund.MAX_REASON_LENGTH) {
            errors.add(error("reason", "SIZE", "reason must be 1 to " + Refund.MAX_REASON_LENGTH + " characters."));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private static NotFoundException paymentNotFound() {
        return new NotFoundException("Payment not found.");
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message);
    }

    private static BusinessRuleException invalid(List<Map<String, String>> errors) {
        return new BusinessRuleException("VALIDATION_ERROR", "One or more fields are invalid.",
                Map.of("errors", errors));
    }
}
