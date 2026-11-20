package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.InvoiceBalancePort;
import com.hms.billing.api.ServicePrice;
import com.hms.billing.api.ServicePriceLookup;
import com.hms.billing.domain.Invoice;
import com.hms.billing.domain.InvoiceCalculator;
import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.billing.domain.InvoiceLine;
import com.hms.billing.infrastructure.InvoiceRepository;
import com.hms.billing.infrastructure.InvoiceRepository.InvoiceRow;
import com.hms.billing.infrastructure.InvoiceRepository.ItemRow;
import com.hms.billing.infrastructure.InvoiceRepository.ListFilter;
import com.hms.billing.infrastructure.ServiceCatalogRepository;
import com.hms.billing.infrastructure.ServiceCatalogRepository.ServiceRow;
import com.hms.clinical.api.EncounterQuery;
import com.hms.clinical.api.EncounterQuery.EncounterSummary;
import com.hms.patient.api.PatientDirectory;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoice drafts and their calculation ([E7-1], BIL-1..4, ADR-0012; api-design.md section 12). An OPD draft is
 * created for an encounter of the patient; its items are replaced as a whole while DRAFT. A catalog item is priced
 * from {@link ServicePriceLookup} at the moment of the change and the price is copied onto the line, so a later
 * catalog price never alters the draft. A price that differs from the catalog, or a line without a catalog service,
 * needs {@code invoice:price-override}. Totals are sums of the rounded lines ({@link InvoiceCalculator}). Every write
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

    public InvoiceService(InvoiceRepository repository, ServiceCatalogRepository catalog, ServicePriceLookup prices,
            EncounterQuery encounters, PatientDirectory patients, AuditRecorder audit, UuidV7Generator ids,
            Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.prices = prices;
        this.encounters = encounters;
        this.patients = patients;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** A new empty OPD draft; the encounter must exist in the organization and belong to the patient. */
    @Transactional
    public InvoiceView createDraft(OrganizationPrincipal actor, String type, UUID patientId, UUID encounterId) {
        UUID org = actor.organizationId();
        if (!"OPD".equals(type)) {
            throw invalid("type", "Only OPD invoices can be created from an encounter; IPD comes with admissions.");
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
        if (status != null && !Invoice.STATUSES.contains(status)) {
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
            boolean mayOverridePrice) {
        UUID org = actor.organizationId();
        requireVersion(version);
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        Instant now = clock.instant();
        List<InvoiceLine> lines = lines(org, inputs, now, mayOverridePrice);
        requireCurrent(row, version);
        repository.replaceItems(org, id, lines, InvoiceCalculator.totals(lines.stream().map(InvoiceLine::totals)
                .toList()), now);
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_ITEMS_UPDATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("items", "subtotal", "discountTotal", "taxTotal", "total")));
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    /** DRAFT to DISCARDED (a draft created by mistake); the encounter can then be invoiced again. */
    @Transactional
    public InvoiceView discard(OrganizationPrincipal actor, UUID id, Long version) {
        UUID org = actor.organizationId();
        requireVersion(version);
        InvoiceRow row = repository.lock(org, id).orElseThrow(InvoiceService::notFound);
        requireDraft(row);
        requireCurrent(row, version);
        repository.setStatus(org, id, Invoice.DISCARDED, clock.instant());
        audit.record(new AuditEntry(org, actor.userId(), "INVOICE_DISCARDED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status")));
        return view(org, repository.find(org, id).orElseThrow(), true);
    }

    // --- InvoiceBalancePort (payment module, [E8-x]) ---

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InvoiceBalance lockForPayment(UUID organizationId, UUID invoiceId) {
        InvoiceRow row = repository.lock(organizationId, invoiceId).orElseThrow(InvoiceService::notFound);
        return new InvoiceBalance(row.status(), row.total(), Money.ZERO, Money.ZERO);
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
    private List<InvoiceLine> lines(UUID org, List<ItemInput> inputs, Instant now, boolean mayOverridePrice) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_ITEMS) {
            throw fieldErrors(List.of(error("items", "SIZE", "items needs 1 to " + MAX_ITEMS + " entries.")));
        }
        List<Map<String, String>> errors = new ArrayList<>();
        List<InvoiceLine> lines = new ArrayList<>();
        boolean overrides = false;
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
            if (errors.size() > before) {
                continue;
            }
            LineTotals check = InvoiceCalculator.line(quantity, unitPrice, Money.ZERO, taxRate);
            if (discount.compareTo(check.gross()) > 0) {
                errors.add(error(p + "discount", "RANGE", "discount must not exceed the line amount."));
                continue;
            }
            lines.add(InvoiceLine.of(in.serviceId(), description, quantity, unitPrice, discount, reason, taxRate));
        }
        if (!errors.isEmpty()) {
            throw fieldErrors(errors);
        }
        if (overrides && !mayOverridePrice) {
            throw new AccessDeniedException("A price other than the catalog price needs invoice:price-override.");
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
                r.taxTotal().toString(), r.total().toString(), r.version(), items, r.createdAt(), r.updatedAt());
    }

    private static InvoiceView.Item item(ItemRow row) {
        InvoiceLine l = row.line();
        LineTotals t = l.totals();
        return new InvoiceView.Item(row.id(), l.serviceId(), l.description(), l.quantity().toPlainString(),
                l.unitPrice().toString(), t.discount().toString(), l.discountReason(), l.taxRate().toPlainString(),
                t.gross().toString(), t.taxable().toString(), t.tax().toString(), t.total().toString());
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
