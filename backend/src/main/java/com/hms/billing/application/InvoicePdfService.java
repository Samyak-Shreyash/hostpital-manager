package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.InvoiceLookup;
import com.hms.billing.api.InvoicePaymentTotals;
import com.hms.billing.api.InvoicePaymentTotals.Totals;
import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.billing.domain.InvoiceLine;
import com.hms.billing.infrastructure.CreditNoteRepository;
import com.hms.billing.infrastructure.InvoiceRepository;
import com.hms.billing.infrastructure.InvoiceRepository.InvoiceRow;
import com.hms.billing.infrastructure.InvoiceRepository.ItemRow;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The invoice PDF ([E7-5]) and the read-only {@link InvoiceLookup} for the payment receipt. A DRAFT (or DISCARDED)
 * invoice has no number and is not printable. Paid and refunded come from the payment module through
 * {@link InvoicePaymentTotals}; {@code outstanding = total - credited - paid + refunded} (api-design.md section 12).
 * Generating the PDF is audited, field names only.
 */
@Service
public class InvoicePdfService implements InvoiceLookup {

    /** A printable invoice: its bytes and a business-key based file name. */
    public record PdfFile(byte[] bytes, String filename) {
    }

    private final InvoiceRepository repository;
    private final CreditNoteRepository creditNotes;
    private final InvoicePaymentTotals paymentTotals;
    private final PatientDirectory patients;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;

    public InvoicePdfService(InvoiceRepository repository, CreditNoteRepository creditNotes,
            InvoicePaymentTotals paymentTotals, PatientDirectory patients, OrganizationDirectory organizations,
            AuditRecorder audit) {
        this.repository = repository;
        this.creditNotes = creditNotes;
        this.paymentTotals = paymentTotals;
        this.patients = patients;
        this.organizations = organizations;
        this.audit = audit;
    }

    @Transactional
    public PdfFile pdf(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        InvoiceRow row = repository.find(org, id).orElseThrow(() -> new NotFoundException("Invoice not found."));
        if (row.invoiceNo() == null) {
            throw new ConflictException("INVALID_STATE_TRANSITION", "Only an issued invoice can be printed.");
        }
        OrganizationSummary organization = organizations.find(org)
                .orElseThrow(() -> new NotFoundException("Invoice not found."));
        PatientBrief patient = patients.briefs(org, List.of(row.patientId())).get(row.patientId());
        if (patient == null) {
            throw new NotFoundException("Invoice not found.");
        }
        Money credited = creditNotes.creditedOn(org, id);
        Totals totals = paymentTotals.totals(org, id);
        Money outstanding = row.total().minus(credited).minus(totals.paid()).plus(totals.refunded());
        List<InvoicePdf.Line> lines = repository.items(org, id).stream().map(InvoicePdfService::line).toList();
        String date = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(ZoneId.of(organization.timezone()))
                .format(row.issuedAt());
        byte[] bytes = InvoicePdf.render(new InvoicePdf.Content(organization.name(), organization.address(),
                row.invoiceNo(), date, patient.name(), patient.uhid(), row.status(),
                Invoice.CANCELLED.equals(row.status()), lines, row.subtotal().toString(),
                row.discountTotal().toString(), row.taxTotal().toString(), row.total().toString(),
                credited.toString(), totals.paid().toString(), totals.refunded().toString(), outstanding.toString()));
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_PDF_GENERATED", InvoiceService.ENTITY, id,
                AuditOutcome.SUCCESS, List.of("status", "invoiceNo")));
        return new PdfFile(bytes, "invoice-" + row.invoiceNo().replace('/', '-') + ".pdf");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<InvoiceRef> find(UUID organizationId, UUID invoiceId) {
        return repository.find(organizationId, invoiceId)
                .map(r -> new InvoiceRef(r.id(), r.invoiceNo(), r.patientId(), r.status()));
    }

    private static InvoicePdf.Line line(ItemRow row) {
        InvoiceLine l = row.line();
        LineTotals t = l.totals();
        return new InvoicePdf.Line(l.description(), l.quantity().toPlainString(), l.unitPrice().toString(),
                t.discount().toString(), t.tax().toString(), t.total().toString());
    }
}
