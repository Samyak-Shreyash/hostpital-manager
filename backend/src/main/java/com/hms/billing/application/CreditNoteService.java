package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.domain.Invoice;
import com.hms.billing.infrastructure.CreditNoteRepository;
import com.hms.billing.infrastructure.CreditNoteRepository.CreditNoteRow;
import com.hms.billing.infrastructure.InvoiceRepository;
import com.hms.billing.infrastructure.InvoiceRepository.InvoiceRow;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.numbering.FinancialYear;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credit notes against issued invoices ([E7-4], ADR-0012). In one transaction: the invoice row lock, the state check
 * (ISSUED, PARTIALLY_PAID or PAID, else 409 INVALID_STATE_TRANSITION), {@code sum of credit notes <= total} (else
 * 422 CREDIT_EXCEEDS_TOTAL), then the CREDIT_NOTE number is allocated last. When the credit notes reach the total
 * and nothing is paid net (the invoice is ISSUED: payments and refunds keep the status in sync, and billing never
 * reads payment tables), the invoice becomes CANCELLED. Credit notes are append-only; audit
 * {@code CREDIT_NOTE_ISSUED} lists field names only.
 */
@Service
public class CreditNoteService {

    static final String SEQUENCE = "CREDIT_NOTE";
    static final String ENTITY = "CREDIT_NOTE";
    static final int MAX_REASON_LENGTH = 500;
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final Set<String> CREDITABLE = Set.of(Invoice.ISSUED, Invoice.PARTIALLY_PAID, Invoice.PAID);

    private final InvoiceRepository invoices;
    private final CreditNoteRepository creditNotes;
    private final OrganizationDirectory organizations;
    private final DocumentNumberAllocator numbers;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public CreditNoteService(InvoiceRepository invoices, CreditNoteRepository creditNotes,
            OrganizationDirectory organizations, DocumentNumberAllocator numbers, AuditRecorder audit,
            UuidV7Generator ids, Clock clock) {
        this.invoices = invoices;
        this.creditNotes = creditNotes;
        this.organizations = organizations;
        this.numbers = numbers;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public CreditNoteView issue(OrganizationPrincipal actor, UUID invoiceId, Money amount, String reason) {
        validate(amount, reason);
        UUID org = actor.organizationId();
        InvoiceRow invoice = invoices.lock(org, invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found."));
        if (!CREDITABLE.contains(invoice.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "Only an ISSUED, PARTIALLY_PAID or PAID invoice accepts credit notes.");
        }
        Money credited = creditNotes.creditedOn(org, invoiceId);
        Money newCredited = credited.plus(amount);
        if (newCredited.compareTo(invoice.total()) > 0) {
            throw new BusinessRuleException("CREDIT_EXCEEDS_TOTAL",
                    "Credit notes may not exceed the invoice total; creditable is "
                            + invoice.total().minus(credited) + ".");
        }
        Instant now = clock.instant();
        ZoneId zone = organizations.find(org).map(OrganizationSummary::timezone).map(ZoneId::of)
                .orElseThrow(() -> new IllegalStateException("organization without a time zone"));
        String fy = FinancialYear.of(now, zone);
        long sequence = numbers.next(org, SEQUENCE, "FY" + fy); // last lock: after the invoice row
        String number = "CN-" + fy + "/" + String.format("%06d", sequence);
        CreditNoteRow row = new CreditNoteRow(ids.next(), invoiceId, amount, reason.strip(), number, now,
                actor.userId());
        creditNotes.insert(org, row);
        String status = invoice.status();
        List<String> fields = new ArrayList<>(List.of("invoiceId", "amount", "reason", "creditNoteNo", "issuedAt",
                "issuedBy"));
        if (newCredited.compareTo(invoice.total()) == 0 && Invoice.ISSUED.equals(status)) {
            status = Invoice.CANCELLED;
            invoices.setStatus(org, invoiceId, status, now);
            fields.add("invoice.status");
        }
        audit.record(new AuditEntry(org, actor.userId(), "CREDIT_NOTE_ISSUED", ENTITY, row.id(),
                AuditOutcome.SUCCESS, fields));
        return new CreditNoteView(row.id(), invoiceId, amount, number, now, actor.userId(), newCredited, status);
    }

    private static void validate(Money amount, String reason) {
        List<Map<String, String>> errors = new ArrayList<>();
        if (amount == null) {
            errors.add(error("amount", "REQUIRED", "amount is required."));
        } else if (amount.compareTo(Money.ZERO) <= 0 || amount.amount().compareTo(MAX_AMOUNT) > 0) {
            errors.add(error("amount", "RANGE", "amount must be greater than 0.00."));
        }
        if (reason == null || reason.isBlank() || reason.strip().length() > MAX_REASON_LENGTH) {
            errors.add(error("reason", "SIZE", "reason must be 1 to " + MAX_REASON_LENGTH + " characters."));
        }
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("VALIDATION_ERROR", "One or more fields are invalid.",
                    Map.of("errors", errors));
        }
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message);
    }
}
