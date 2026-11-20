package com.hms.payment.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.InvoiceBalancePort;
import com.hms.billing.api.InvoiceBalancePort.InvoiceBalance;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.payment.domain.Payment;
import com.hms.payment.infrastructure.PaymentRepository;
import com.hms.payment.infrastructure.PaymentRepository.ListFilter;
import com.hms.payment.infrastructure.PaymentRepository.PaymentRow;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.numbering.FinancialYear;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payments against issued invoices ([E7-3], BIL-6, ADR-0012; api-design.md section 12). Recording one runs in one
 * transaction: the Idempotency-Key advisory lock, then the invoice row lock through billing's
 * {@link InvoiceBalancePort}, then the amounts paid and refunded are summed from this module's own tables (billing
 * never depends on payment), the outstanding amount is checked, and the RECEIPT number is allocated last. The
 * invoice status follows the new balance. One {@code PAYMENT_RECORDED} audit event (field names only) covers the
 * payment and the invoice status change.
 */
@Service
public class PaymentService {

    static final String CREATE_ENDPOINT = "POST /api/v1/invoices/{id}/payments";
    static final String RECEIPT_SEQUENCE = "RECEIPT";
    static final String ENTITY = "PAYMENT";
    static final int MAX_PAGE_SIZE = 100;
    /** The largest amount a NUMERIC(14,2) column holds. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    private final PaymentRepository repository;
    private final InvoiceBalancePort invoices;
    private final OrganizationDirectory organizations;
    private final DocumentNumberAllocator numbers;
    private final IdempotencyService idempotency;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public PaymentService(PaymentRepository repository, InvoiceBalancePort invoices,
            OrganizationDirectory organizations, DocumentNumberAllocator numbers, IdempotencyService idempotency,
            AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.invoices = invoices;
        this.organizations = organizations;
        this.numbers = numbers;
        this.idempotency = idempotency;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** The request hash of a payment: route, invoice id and the canonical body fields in order. */
    public static String createRequestHash(UUID invoiceId, Money amount, String mode, String referenceNo) {
        return IdempotencyService.hash(CREATE_ENDPOINT, invoiceId, amount == null ? null : amount.toString(), mode,
                referenceNo);
    }

    @Transactional
    public IdempotencyService.Result<RecordedPayment> record(OrganizationPrincipal actor, UUID invoiceId,
            Money amount, String mode, String referenceNo, String idempotencyKey, String requestHash) {
        IdempotencyService.requireKey(idempotencyKey);
        validate(amount, mode, referenceNo);
        return idempotency.execute(actor.organizationId(), CREATE_ENDPOINT, idempotencyKey, requestHash, 201,
                RecordedPayment.class, () -> doRecord(actor, invoiceId, amount, mode, referenceNo));
    }

    private RecordedPayment doRecord(OrganizationPrincipal actor, UUID invoiceId, Money amount, String mode,
            String referenceNo) {
        UUID org = actor.organizationId();
        InvoiceBalance balance = invoices.lockForPayment(org, invoiceId); // 404 for unknown / other-org ids
        if (!Payment.payable(balance.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "Only an ISSUED or PARTIALLY_PAID invoice accepts payments.");
        }
        Money paid = repository.paidOn(org, invoiceId);
        Money refunded = repository.refundedOn(org, invoiceId);
        Money outstanding = Payment.outstanding(balance.payable(), paid, refunded);
        if (amount.compareTo(outstanding) > 0) {
            throw new ConflictException("EXCEEDS_OUTSTANDING", "Outstanding is " + outstanding + ".");
        }
        Instant now = clock.instant();
        ZoneId zone = organizations.find(org).map(OrganizationSummary::timezone).map(ZoneId::of)
                .orElseThrow(() -> new IllegalStateException("organization without a time zone"));
        String fy = FinancialYear.of(now, zone);
        long sequence = numbers.next(org, RECEIPT_SEQUENCE, "FY" + fy); // last lock: after the invoice row
        String receiptNo = "RCT-" + fy + "/" + String.format("%06d", sequence);
        PaymentRow row = new PaymentRow(ids.next(), invoiceId, amount, mode, referenceNo, receiptNo, now,
                actor.userId());
        repository.insert(org, row);
        Money newPaid = paid.plus(amount);
        String invoiceStatus = Payment.invoiceStatus(balance.payable(), newPaid, refunded);
        invoices.applyPaymentStatus(org, invoiceId, invoiceStatus);
        List<String> fields = new ArrayList<>(List.of("invoiceId", "amount", "mode", "referenceNo", "receiptNo",
                "receivedAt", "receivedBy"));
        if (!invoiceStatus.equals(balance.status())) {
            fields.add("invoice.status");
        }
        audit.record(new AuditEntry(org, actor.userId(), "PAYMENT_RECORDED", ENTITY, row.id(), AuditOutcome.SUCCESS,
                fields));
        return new RecordedPayment(row.id(), invoiceId, amount, mode, referenceNo, receiptNo, now, actor.userId(),
                Payment.outstanding(balance.payable(), newPaid, refunded), invoiceStatus);
    }

    @Transactional(readOnly = true)
    public PageResult<PaymentView> list(OrganizationPrincipal actor, Instant from, Instant to, String mode, int page,
            int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (mode != null && !Payment.MODES.contains(mode)) {
            throw invalid(List.of(error("mode", "INVALID", "Unknown payment mode.")));
        }
        UUID org = actor.organizationId();
        ListFilter filter = new ListFilter(from, to, mode);
        List<PaymentView> items = repository.list(org, filter, size, (long) page * size).stream()
                .map(PaymentView::of).toList();
        return new PageResult<>(items, page, size, repository.count(org, filter));
    }

    private static void validate(Money amount, String mode, String referenceNo) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (amount == null) {
            errors.add(error("amount", "REQUIRED", "amount is required."));
        } else if (amount.compareTo(Money.ZERO) <= 0 || amount.amount().compareTo(MAX_AMOUNT) > 0) {
            errors.add(error("amount", "RANGE", "amount must be greater than 0.00."));
        }
        if (mode == null) {
            errors.add(error("mode", "REQUIRED", "mode is required."));
        } else if (!Payment.MODES.contains(mode)) {
            errors.add(error("mode", "INVALID", "mode must be one of " + String.join(", ",
                    Payment.MODES.stream().sorted().toList()) + "."));
        }
        if (referenceNo != null && (referenceNo.isBlank() || referenceNo.length() > Payment.MAX_REFERENCE_LENGTH)) {
            errors.add(error("referenceNo", "SIZE",
                    "referenceNo must be 1 to " + Payment.MAX_REFERENCE_LENGTH + " characters."));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message);
    }

    private static BusinessRuleException invalid(List<Map<String, String>> errors) {
        return new BusinessRuleException("VALIDATION_ERROR", "One or more fields are invalid.",
                Map.of("errors", errors));
    }
}
