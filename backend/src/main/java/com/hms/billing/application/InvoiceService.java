package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.ChargeSource;
import com.hms.billing.api.ChargeSource.AdmissionInfo;
import com.hms.billing.api.ChargeSource.ChargeLine;
import com.hms.billing.api.InvoiceBalancePort;
import com.hms.billing.api.InvoiceIssued;
import com.hms.billing.api.ServicePrice;
import com.hms.billing.api.ServicePriceLookup;
import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceCalculator;
import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.billing.domain.InvoiceLine;
import com.hms.billing.infrastructure.CreditNoteRepository;
import com.hms.billing.infrastructure.InvoiceRepository;
import com.hms.billing.infrastructure.InvoiceRepository.InvoiceRow;
import com.hms.billing.infrastructure.InvoiceRepository.ItemRow;
import com.hms.billing.infrastructure.InvoiceRepository.ListFilter;
import com.hms.billing.infrastructure.ServiceCatalogRepository;
import com.hms.billing.infrastructure.ServiceCatalogRepository.ServiceRow;
import com.hms.clinical.api.EncounterQuery;
import com.hms.clinical.api.EncounterQuery.EncounterSummary;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.patient.api.PatientDirectory;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.numbering.FinancialYear;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.web.idempotency.IdempotencyService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice drafts and their calculation ([E7-1], BIL-1..4, ADR-0012; api-design.md section 12). An OPD draft is
 * created for an encounter of the patient; its items are replaced as a whole while DRAFT. A catalog item is priced
 * from {@link ServicePriceLookup} at the moment of the change and the price is copied onto the line, so a later
 * catalog price never alters the draft. A price that differs from the catalog, or a line without a catalog service,
 * needs {@code invoice:price-override}. A line discount needs {@code invoice:discount} and a reason ([E7-8]); an
 * invoice-level percent discount is distributed half-up to the lines. Totals are sums of the rounded lines ({@link InvoiceCalculator}). Every write
 * locks the invoice row and decides on the locked state and version. Audit records field names only.
 */
@Service
public class InvoiceService implements InvoiceBalancePort {

    static final String ENTITY = "INVOICE";
    static final int MAX_ITEMS = 100;
    static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal MIN_QUANTITY = new BigDecimal("0.01");
    private static final BigDecimal MAX_QUANTITY = new BigDecimal("9999");
    private static final BigDecimal MAX_TAX_RATE = new BigDecimal("100");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** The largest amount a NUMERIC(14,2) column holds; each line total and the invoice total stay within it. */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

    /** One requested line. Quantity and tax rate are decimal strings; money is a {@link Money} decimal string. */
    public record ItemInput(UUID serviceId, String description, String quantity, Money unitPrice, Money discount,
            String discountReason, String taxRate) {
    }

    private final InvoiceRepository repository;
    private final ServiceCatalogRepository catalog;
    private final ServicePriceLookup prices;
    private final EncounterQuery encounters;
    private final PatientDirectory patients;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final OrganizationDirectory organizations;
    private final DocumentNumberAllocator numbers;
    private final IdempotencyService idempotency;
    private final ApplicationEventPublisher events;
    private final CreditNoteRepository creditNotes;
    private final ChargeSource charges;

    public InvoiceService(InvoiceRepository repository, ServiceCatalogRepository catalog, ServicePriceLookup prices,
            EncounterQuery encounters, PatientDirectory patients, AuditRecorder audit, UuidV7Generator ids,
            Clock clock, OrganizationDirectory organizations, DocumentNumberAllocator numbers,
            IdempotencyService idempotency, ApplicationEventPublisher events, CreditNoteRepository creditNotes,
            ChargeSource charges) {
        this.charges = charges;
        this.creditNotes = creditNotes;
        this.organizations = organizations;
        this.numbers = numbers;
        this.idempotency = idempotency;
        this.events = events;
        this.repository = repository;
        this.catalog = catalog;
        this.prices = prices;
        this.encounters = encounters;
        this.patients = patients;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * A new draft: OPD (empty, for an encounter of the patient; the encounter must exist in the organization) or IPD
     * ({@link #createIpdDraft}). {@code admissionId} and {@code kind} are for IPD only.
     */
    @Transactional
    public InvoiceView createDraft(OrganizationPrincipal actor, String type, UUID patientId, UUID encounterId,
            UUID admissionId, String kind) {
        if ("IPD".equals(type)) {
            return createIpdDraft(actor, patientId, admissionId, kind);
        }
        UUID org = actor.organizationId();
        if (!"OPD".equals(type)) {
            throw invalid("type", "type must be OPD or IPD.");
        }
        if (patientId == null) {
            throw invalid("patientId", "patientId is required.");
        }
        if (encounterId == null) {
            throw invalid("encounterId", "encounterId is required for an OPD invoice.");
        }
        if (patients.briefs(org, List.of(patientId)).get(patientId) == null) {
            throw new NotFoundException("Patient not found.");
        }
        EncounterSummary encounter = encounters.find(org, encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found."));
        if (!encounter.patientId().equals(patientId)) {
            throw invalid("encounterId", "The encounter belongs to another patient.");
        }
        UUID id = ids.next();
        if (!repository.insertOpdDraft(id, org, patientId, encounterId, actor.userId(), clock.instant())) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "The encounter already has an invoice; discard it or use it.");
        }
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("type", "patientId", "encounterId", "status")));
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    /**
     * [E8-6] An IPD draft (ruling R4) whose first items are the admission's room-charge lines from
     * {@link ChargeSource}, unit prices snapshot. INTERIM needs the admission ADMITTED or DISCHARGE_INITIATED and
     * charges up to now; FINAL needs DISCHARGE_INITIATED and charges up to the planned discharge, at most one live
     * FINAL per admission ({@code 409 DUPLICATE_FINAL_INVOICE}). Only dates after the latest
     * {@code roomChargesThrough} of the admission's live IPD invoices are charged; drafts of one admission are
     * serialised by a transaction advisory lock (billing never locks the admission row). An INTERIM with no
     * chargeable day is {@code 422 BUSINESS_RULE_VIOLATION}.
     */
    private InvoiceView createIpdDraft(OrganizationPrincipal actor, UUID patientId, UUID admissionId, String kind) {
        UUID org = actor.organizationId();
        List<Map<String, String>> errors = new ArrayList<>();
        if (patientId == null) {
            errors.add(error("patientId", "REQUIRED", "patientId is required."));
        }
        if (admissionId == null) {
            errors.add(error("admissionId", "REQUIRED", "admissionId is required for an IPD invoice."));
        }
        if (!Invoice.INTERIM.equals(kind) && !Invoice.FINAL.equals(kind)) {
            errors.add(error("kind", "INVALID", "kind must be INTERIM or FINAL for an IPD invoice."));
        }
        if (!errors.isEmpty()) {
            throw fieldErrors(errors);
        }
        repository.lockAdmissionInvoicing(org, admissionId);
        AdmissionInfo admission = charges.admission(org, admissionId)
                .orElseThrow(() -> new NotFoundException("Admission not found."));
        if (!admission.patientId().equals(patientId)) {
            throw invalid("patientId", "The admission belongs to another patient.");
        }
        boolean fin = Invoice.FINAL.equals(kind);
        boolean allowed = fin ? "DISCHARGE_INITIATED".equals(admission.status())
                : "ADMITTED".equals(admission.status()) || "DISCHARGE_INITIATED".equals(admission.status());
        if (!allowed) {
            throw new ConflictException("INVALID_STATE_TRANSITION", fin
                    ? "A final invoice needs the admission in DISCHARGE_INITIATED."
                    : "An interim invoice needs an active admission.");
        }
        Instant now = clock.instant();
        Instant endAt = fin ? admission.chargeEndAt() : now;
        if (endAt == null) {
            throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "The admission has no planned discharge time.");
        }
        LocalDate after = repository.roomChargesThrough(org, admissionId);
        List<ChargeLine> room = charges.roomCharges(org, admissionId, after, endAt);
        if (room.isEmpty() && !fin) {
            throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "There are no room days to charge yet.");
        }
        UUID id = ids.next();
        if (!repository.insertIpdDraft(id, org, patientId, admissionId, kind, actor.userId(), now)) {
            throw new ConflictException("DUPLICATE_FINAL_INVOICE",
                    "The admission already has a final invoice; discard it or use it.");
        }
        if (!room.isEmpty()) {
            List<InvoiceLine> lines = room.stream().map(c -> InvoiceLine.of(c.serviceId(), c.description(),
                    BigDecimal.valueOf(c.quantity()).setScale(2), c.unitPrice(), Money.ZERO, null,
                    BigDecimal.ZERO.setScale(2))).toList();
            repository.replaceItems(org, id, lines, Collections.nCopies(lines.size(), InvoiceRepository.ROOM),
                    InvoiceCalculator.totals(lines.stream().map(InvoiceLine::totals).toList()), now);
            repository.setRoomChargesThrough(org, id, room.stream().map(ChargeLine::to)
                    .max(LocalDate::compareTo).orElseThrow());
        } else if (after != null) {
            repository.setRoomChargesThrough(org, id, after);
        }
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("type", "patientId", "admissionId", "kind", "status", "items", "roomChargesThrough",
                        "total")));
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    /** Reading an invoice (its lines name the services a patient received) is audited. */
    @Transactional
    public InvoiceView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        InvoiceRow row = repository.find(org, id).orElseThrow(InvoiceService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "INVOICE_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return view(org, row, true);
    }

    @Transactional(readOnly = true)
    public PageResult<InvoiceView> list(OrganizationPrincipal actor, UUID patientId, String status, Instant from,
            Instant to, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        // One status or several separated by commas (the outstanding list asks for ISSUED,PARTIALLY_PAID).
        if (status != null && !Invoice.STATUSES.containsAll(List.of(status.split(",", -1)))) {
            throw invalid("status", "Unknown invoice status.");
        }
        UUID org = actor.organizationId();
        ListFilter filter = new ListFilter(patientId, status, from, to);
        List<InvoiceView> items = repository.list(org, filter, size, (long) page * size).stream()
                .map(r -> view(org, r, false)).toList();
        return new PageResult<>(items, page, size, repository.count(org, filter));
    }

    /** Replaces the items of a DRAFT and recomputes its totals. */
    @Transactional
    public InvoiceView replaceItems(OrganizationPrincipal actor, UUID id, Long version, List<ItemInput> inputs,
            boolean mayOverridePrice, boolean mayDiscount) {
        UUID org = actor.organizationId();
        requireVersion(version);
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        Instant now = clock.instant();
        List<ItemRow> roomRows = repository.items(org, id).stream().filter(ItemRow::room).toList();
        List<InvoiceLine> lines = new ArrayList<>(roomRows.stream().map(ItemRow::line).toList());
        List<ItemInput> others = withoutRoomLines(inputs, roomRows);
        if (roomRows.size() + others.size() > MAX_ITEMS) {
            throw fieldErrors(List.of(error("items", "SIZE", "items needs 1 to " + MAX_ITEMS + " entries.")));
        }
        if (roomRows.isEmpty() || !others.isEmpty()) {
            lines.addAll(lines(org, others, now, mayOverridePrice, mayDiscount));
        }
        List<String> kinds = new ArrayList<>(Collections.nCopies(roomRows.size(), InvoiceRepository.ROOM));
        kinds.addAll(Collections.nCopies(lines.size() - roomRows.size(), InvoiceRepository.STANDARD));
        requireCurrent(row, version);
        repository.replaceItems(org, id, lines, kinds, InvoiceCalculator.totals(lines.stream()
                .map(InvoiceLine::totals).toList()), now);
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_ITEMS_UPDATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("items", "subtotal", "discountTotal", "taxTotal", "total")));
        if (lines.stream().anyMatch(l -> positive(l.discount()))) {
            auditDiscount(org, actor, id);
        }
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    /**
     * Invoice-level discount of {@code percent} (0 to 100) on a DRAFT: every line gets
     * {@code round_half_up(gross x percent / 100)} and the reason, then is recalculated. Replaces line discounts.
     */
    @Transactional
    public InvoiceView applyPercentDiscount(OrganizationPrincipal actor, UUID id, Long version, String percentText,
            String reasonText) {
        UUID org = actor.organizationId();
        requireVersion(version);
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        List<Map<String, String>> errors = new ArrayList<>();
        BigDecimal percent = decimal(percentText, "percent", true, BigDecimal.ZERO, HUNDRED, errors);
        String reason = text(reasonText, "reason", errors);
        if (reason == null) {
            errors.add(error("reason", "REQUIRED", "reason is required."));
        }
        if (!errors.isEmpty()) {
            throw fieldErrors(errors);
        }
        requireCurrent(row, version);
        List<ItemRow> currentRows = repository.items(org, id);
        List<InvoiceLine> current = currentRows.stream().map(ItemRow::line).toList();
        if (current.isEmpty()) {
            throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "The invoice has no items to discount.");
        }
        List<InvoiceLine> lines = new ArrayList<>();
        for (InvoiceLine l : current) {
            Money discount = l.totals().gross().percentage(percent);
            lines.add(InvoiceLine.of(l.serviceId(), l.description(), l.quantity(), l.unitPrice(), discount,
                    positive(discount) ? reason : null, l.taxRate()));
        }
        repository.replaceItems(org, id, lines, currentRows.stream().map(ItemRow::kind).toList(),
                InvoiceCalculator.totals(lines.stream().map(InvoiceLine::totals).toList()), clock.instant());
        auditDiscount(org, actor, id);
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    /**
     * [E8-6] Room-charge lines of an IPD draft are kept as generated: every stored ROOM line must appear in the
     * request unchanged (service, description, quantity, unit price, discount, tax rate) and is matched off; the
     * remaining inputs are ordinary lines. A room line changed or left out is {@code 422 VALIDATION_ERROR}.
     */
    private static List<ItemInput> withoutRoomLines(List<ItemInput> inputs, List<ItemRow> roomRows) {
        if (roomRows.isEmpty()) {
            return inputs;
        }
        if (inputs == null || inputs.size() > MAX_ITEMS) {
            throw fieldErrors(List.of(error("items", "SIZE", "items needs 1 to " + MAX_ITEMS + " entries.")));
        }
        List<ItemInput> rest = new ArrayList<>(inputs);
        for (ItemRow room : roomRows) {
            InvoiceLine l = room.line();
            ItemInput match = rest.stream().filter(in -> in != null && sameAsRoom(in, l)).findFirst().orElse(null);
            if (match == null) {
                throw fieldErrors(List.of(error("items", "ROOM_LINE_REQUIRED",
                        "Room-charge lines must be kept unchanged.")));
            }
            rest.remove(match);
        }
        return rest;
    }

    private static boolean sameAsRoom(ItemInput in, InvoiceLine l) {
        return l.serviceId().equals(in.serviceId())
                && in.description() != null && l.description().equals(in.description().strip())
                && sameDecimal(in.quantity(), l.quantity())
                && (in.unitPrice() == null || in.unitPrice().equals(l.unitPrice()))
                && (in.discount() == null ? !positive(l.discount()) : in.discount().equals(l.discount()))
                && (in.taxRate() == null || in.taxRate().isBlank() ? l.taxRate().signum() == 0
                        : sameDecimal(in.taxRate(), l.taxRate()));
    }

    private static boolean sameDecimal(String text, BigDecimal value) {
        try {
            return text != null && new BigDecimal(text.strip()).compareTo(value) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Field names only; the reason is free text and never goes to the audit trail. */
    private void auditDiscount(UUID org, OrganizationPrincipal actor, UUID id) {
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_DISCOUNT_APPLIED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("items", "discountTotal", "taxTotal", "total")));
    }

    private static boolean positive(Money amount) {
        return amount.compareTo(Money.ZERO) > 0;
    }

    /** DRAFT to DISCARDED (a draft created by mistake); the encounter can then be invoiced again. */
    @Transactional
    public InvoiceView discard(OrganizationPrincipal actor, UUID id, Long version) {
        UUID org = actor.organizationId();
        requireVersion(version);
        repository.requireNoLaterIpdCoverage(org, id); // advisory lock before the invoice row
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        requireCurrent(row, version);
        repository.setStatus(org, id, Invoice.DISCARDED, clock.instant());
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_DISCARDED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status")));
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    static final String ISSUE_ENDPOINT = "POST /api/v1/invoices/{id}/issue";
    static final String INVOICE_SEQUENCE = "INVOICE";

    /** The request hash of an issue call: the route and the invoice id (the request has no body). */
    public static String issueRequestHash(UUID id) {
        return IdempotencyService.hash(ISSUE_ENDPOINT, id);
    }

    /**
     * DRAFT to ISSUED ([E7-2], BIL-5, ADR-0012) under {@code idempotencyKey}: a replay of the key returns the stored
     * response. Locks the invoice, requires DRAFT and at least one item, recomputes the totals from the stored lines,
     * then allocates the gapless INVOICE number of the financial year (April to March in the organization's time
     * zone) last, so the counter row is held as briefly as possible and a rollback returns the number.
     */
    @Transactional
    public IdempotencyService.Result<InvoiceView> issue(OrganizationPrincipal actor, UUID id, String idempotencyKey,
            String requestHash) {
        IdempotencyService.requireKey(idempotencyKey);
        return idempotency.execute(actor.organizationId(), ISSUE_ENDPOINT, idempotencyKey, requestHash, 200,
                InvoiceView.class, () -> doIssue(actor, id));
    }

    private InvoiceView doIssue(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        List<InvoiceLine> lines = repository.items(org, id).stream().map(ItemRow::line).toList();
        if (lines.isEmpty()) {
            throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "An invoice needs at least one item to issue.");
        }
        InvoiceCalculator.InvoiceTotals totals = InvoiceCalculator.totals(lines.stream().map(InvoiceLine::totals)
                .toList());
        Instant now = clock.instant();
        ZoneId zone = organizations.find(org).map(OrganizationSummary::timezone).map(ZoneId::of)
                .orElseThrow(() -> new IllegalStateException("organization without a time zone"));
        String fy = financialYear(LocalDate.ofInstant(now, zone));
        long sequence = numbers.next(org, INVOICE_SEQUENCE, "FY" + fy); // last lock: after the invoice row
        String invoiceNo = fy + "/" + String.format("%06d", sequence);
        repository.markIssued(org, id, invoiceNo, actor.userId(), totals, now);
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_ISSUED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status", "invoiceNo", "issuedAt", "issuedBy", "subtotal", "discountTotal", "taxTotal",
                        "total")));
        InvoiceRow issued = repository.find(org, id).orElseThrow();
        events.publishEvent(new InvoiceIssued(org, id, issued.patientId(), invoiceNo, issued.total().toString(),
                issued.issuedAt()));
        return view(org, issued, true);
    }

    /** {@code 2026-27} for any date from 1 April 2026 to 31 March 2027. */
    static String financialYear(LocalDate date) {
        return FinancialYear.of(date);
    }

    // --- InvoiceBalancePort (payment module, [E8-x]) ---

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceBalance lockForPayment(UUID organizationId, UUID invoiceId) {
        InvoiceRow row = repository.lock(organizationId, invoiceId).orElseThrow(InvoiceService::notFound);
        return new InvoiceBalance(row.status(), row.total(), creditNotes.creditedOn(organizationId, invoiceId));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void applyPaymentStatus(UUID organizationId, UUID invoiceId, String newStatus) {
        InvoiceRow row = repository.lock(organizationId, invoiceId).orElseThrow(InvoiceService::notFound);
        if (!Invoice.canApplyPaymentStatus(row.status(), newStatus)) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "Only an issued invoice can move between ISSUED, PARTIALLY_PAID and PAID.");
        }
        if (!row.status().equals(newStatus)) {
            repository.setStatus(organizationId, invoiceId, newStatus, clock.instant());
        }
    }

    // --- internals ---

    /** Validates and prices every line; field errors (422) first, then the price-override permission (403). */
    private List<InvoiceLine> lines(UUID org, List<ItemInput> inputs, Instant now, boolean mayOverridePrice,
            boolean mayDiscount) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_ITEMS) {
            throw fieldErrors(List.of(error("items", "SIZE", "items needs 1 to " + MAX_ITEMS + " entries.")));
        }
        List<Map<String, String>> errors = new ArrayList<>();
        List<Map<String, String>> tooLarge = new ArrayList<>();
        List<InvoiceLine> lines = new ArrayList<>();
        boolean overrides = false;
        boolean discounts = false;
        for (int i = 0; i < inputs.size(); i++) {
            ItemInput in = inputs.get(i);
            String p = "items[" + i + "].";
            if (in == null) {
                errors.add(error("items[" + i + "]", "REQUIRED", "An item must not be null."));
                continue;
            }
            int before = errors.size();
            BigDecimal quantity = decimal(in.quantity(), p + "quantity", true, MIN_QUANTITY, MAX_QUANTITY, errors);
            BigDecimal taxRate = decimal(in.taxRate(), p + "taxRate", false, BigDecimal.ZERO, MAX_TAX_RATE, errors);
            Money unitPrice = in.unitPrice();
            String description = text(in.description(), p + "description", errors);
            if (in.serviceId() != null) {
                Optional<ServiceRow> service = catalog.findService(org, in.serviceId(), now);
                Optional<ServicePrice> price = prices.priceAt(org, in.serviceId(), now);
                if (service.isEmpty() || !service.get().active() || price.isEmpty()) {
                    errors.add(error(p + "serviceId", "UNKNOWN",
                            "The service does not exist, is inactive or has no current price."));
                } else {
                    Money catalogPrice = price.get().amount();
                    if (unitPrice == null) {
                        unitPrice = catalogPrice;
                    } else if (!unitPrice.equals(catalogPrice)) {
                        overrides = true;
                    }
                    if (description == null) {
                        description = service.get().name();
                    }
                }
            } else {
                overrides = true;
                if (unitPrice == null) {
                    errors.add(error(p + "unitPrice", "REQUIRED", "unitPrice is required without a serviceId."));
                }
                if (description == null) {
                    errors.add(error(p + "description", "REQUIRED", "description is required without a serviceId."));
                }
            }
            if (unitPrice != null && unitPrice.isNegative()) {
                errors.add(error(p + "unitPrice", "RANGE", "unitPrice must not be negative."));
            }
            Money discount = in.discount() == null ? Money.ZERO : in.discount();
            if (discount.isNegative()) {
                errors.add(error(p + "discount", "RANGE", "discount must not be negative."));
            }
            String reason = text(in.discountReason(), p + "discountReason", errors);
            if (positive(discount) && reason == null && errors.size() == before) {
                errors.add(error(p + "discountReason", "REQUIRED", "A discount needs a reason."));
            }
            if (errors.size() > before) {
                continue;
            }
            // Check against NUMERIC(14,2) before any Money arithmetic: a larger amount is a field error, not a 500.
            BigDecimal gross = unitPrice.amount().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
            if (gross.compareTo(MAX_AMOUNT) > 0) {
                errors.add(error(p + "unitPrice", "RANGE",
                        "unitPrice x quantity must not exceed " + MAX_AMOUNT.toPlainString() + "."));
                continue;
            }
            if (discount.amount().compareTo(gross) > 0) {
                tooLarge.add(error(p + "discount", "RANGE", "discount must not exceed the line amount."));
                continue;
            }
            BigDecimal taxable = gross.subtract(discount.amount());
            BigDecimal lineTotal = taxable.add(taxable.multiply(taxRate).divide(HUNDRED, 2, RoundingMode.HALF_UP));
            if (lineTotal.compareTo(MAX_AMOUNT) > 0) {
                errors.add(error(p + "unitPrice", "RANGE",
                        "The line total including tax must not exceed " + MAX_AMOUNT.toPlainString() + "."));
                continue;
            }
            discounts |= positive(discount);
            lines.add(InvoiceLine.of(in.serviceId(), description, quantity, unitPrice, discount,
                    positive(discount) ? reason : null, taxRate));
        }
        if (errors.isEmpty() && tooLarge.isEmpty()) {
            BigDecimal subtotal = lines.stream().map(l -> l.totals().gross().amount()).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            BigDecimal total = lines.stream().map(l -> l.totals().total().amount()).reduce(BigDecimal.ZERO,
                    BigDecimal::add);
            if (subtotal.compareTo(MAX_AMOUNT) > 0 || total.compareTo(MAX_AMOUNT) > 0) {
                errors.add(error("items", "RANGE",
                        "The invoice amount must not exceed " + MAX_AMOUNT.toPlainString() + "."));
            }
        }
        if (!errors.isEmpty()) {
            throw fieldErrors(errors);
        }
        if (!tooLarge.isEmpty()) {
            throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "A discount exceeds its line amount.",
                    Map.of("errors", tooLarge));
        }
        if (overrides && !mayOverridePrice) {
            throw new AccessDeniedException("A price other than the catalog price needs invoice:price-override.");
        }
        if (discounts && !mayDiscount) {
            throw new AccessDeniedException("A discount needs invoice:discount.");
        }
        return lines;
    }

    /** A decimal string with at most two places within [min, max]; blank is null (or an error when required). */
    private static BigDecimal decimal(String value, String field, boolean required, BigDecimal min, BigDecimal max,
            List<Map<String, String>> errors) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            if (required) {
                errors.add(error(field, "REQUIRED", field + " is required."));
            }
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(stripped);
        } catch (NumberFormatException e) {
            errors.add(error(field, "FORMAT", field + " must be a decimal number."));
            return null;
        }
        if (parsed.stripTrailingZeros().scale() > 2) {
            errors.add(error(field, "SCALE", field + " has at most 2 decimal places."));
            return null;
        }
        if (parsed.compareTo(min) < 0 || parsed.compareTo(max) > 0) {
            errors.add(error(field, "RANGE", field + " must be " + min.toPlainString() + " to "
                    + max.toPlainString() + "."));
            return null;
        }
        return parsed.setScale(2);
    }

    /** Trims; blank is null; longer than 200 is an error. Never echoes the text. */
    private static String text(String value, String field, List<Map<String, String>> errors) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        if (stripped.length() > 200) {
            errors.add(error(field, "SIZE", field + " must be at most 200 characters."));
        }
        return stripped;
    }

    private static void requireDraft(InvoiceRow row) {
        if (!Invoice.isEditable(row.status())) {
            throw new ConflictException("INVOICE_IMMUTABLE", "Only a draft invoice can be changed.");
        }
    }

    private static void requireCurrent(InvoiceRow row, long version) {
        if (row.version() != version) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
    }

    private static void requireVersion(Long version) {
        if (version == null) {
            throw invalid("version", "version is required.");
        }
    }

    private InvoiceView view(UUID org, InvoiceRow r, boolean withItems) {
        List<InvoiceView.Item> items = withItems ? repository.items(org, r.id()).stream().map(InvoiceService::item)
                .toList() : List.of();
        return new InvoiceView(r.id(), r.type(), r.patientId(), r.encounterId(), r.admissionId(), r.status(),
                r.invoiceNo(), r.issuedAt(), r.subtotal().toString(), r.discountTotal().toString(),
                r.taxTotal().toString(), r.total().toString(), r.version(), items, r.createdAt(), r.updatedAt(), r.kind(),
                r.roomChargesThrough());
    }

    private static InvoiceView.Item item(ItemRow row) {
        InvoiceLine l = row.line();
        LineTotals t = l.totals();
        return new InvoiceView.Item(row.id(), l.serviceId(), l.description(), l.quantity().toPlainString(),
                l.unitPrice().toString(), t.discount().toString(), l.discountReason(), l.taxRate().toPlainString(),
                t.gross().toString(), t.taxable().toString(), t.tax().toString(), t.total().toString(),
                row.kind());
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message);
    }

    private static BusinessRuleException fieldErrors(List<Map<String, String>> errors) {
        return new BusinessRuleException("VALIDATION_ERROR", "One or more fields are invalid.",
                Map.of("errors", errors));
    }

    private static BusinessRuleException invalid(String field, String message) {
        return fieldErrors(List.of(error(field, "INVALID", message)));
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Invoice not found.");
    }
}
