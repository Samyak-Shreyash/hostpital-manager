package com.hms.billing.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.billing.api.ServicePrice;
import com.hms.billing.api.ServicePriceLookup;
import com.hms.billing.infrastructure.ServiceCatalogRepository;
import com.hms.billing.infrastructure.ServiceCatalogRepository.PriceRow;
import com.hms.billing.infrastructure.ServiceCatalogRepository.ServiceRow;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service catalog with versioned prices (ADR-0012, api-design.md). A price is never edited: a change adds a new
 * version that starts at {@code effectiveFrom} and closes the previous open-ended one at that same instant
 * (ranges are half-open, so no instant has two prices). Invoices copy the price, so history is untouched.
 */
@Service
public class ServiceCatalogService implements ServicePriceLookup {

    static final int MAX_PAGE_SIZE = 100;
    private static final String OVERLAP = "PRICE_RANGE_OVERLAP";

    private final ServiceCatalogRepository repository;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ServiceCatalogService(ServiceCatalogRepository repository, AuditRecorder audit, UuidV7Generator ids,
            Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** Instants are stored with microsecond precision; truncating up front keeps comparisons exact. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional(readOnly = true)
    public PageResult<ServiceView> list(OrganizationPrincipal actor, Boolean active, int page, int size) {
        checkPage(page, size);
        UUID org = actor.organizationId();
        List<ServiceView> items = repository.listServices(org, active, now(), size, (long) page * size).stream()
                .map(ServiceCatalogService::view).toList();
        return new PageResult<>(items, page, size, repository.countServices(org, active));
    }

    @Transactional(readOnly = true)
    public ServiceView get(OrganizationPrincipal actor, UUID id) {
        return view(repository.findService(actor.organizationId(), id, now())
                .orElseThrow(ServiceCatalogService::notFound));
    }

    @Transactional
    public ServiceView create(OrganizationPrincipal actor, String code, String name) {
        UUID org = actor.organizationId();
        UUID id = ids.next();
        Instant now = now();
        if (!repository.insertService(id, org, code, name.trim(), now)) {
            throw new ConflictException("DUPLICATE_SERVICE_CODE", "Service code already exists.");
        }
        audit.record(new AuditEntry(org, actor.userId(), "SERVICE_CREATED", "SERVICE", id, AuditOutcome.SUCCESS,
                List.of("code", "name", "active")));
        return view(repository.findService(org, id, now).orElseThrow());
    }

    /** Partial update; {@code version} must match. {@code null} leaves a field alone. Code is immutable. */
    @Transactional
    public ServiceView update(OrganizationPrincipal actor, UUID id, long version, String name, Boolean active) {
        if (name == null && active == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide name and/or active.");
        }
        UUID org = actor.organizationId();
        Instant now = now();
        ServiceRow current = repository.findService(org, id, now).orElseThrow(ServiceCatalogService::notFound);
        if (current.version() != version) {
            throw concurrentModification();
        }
        List<String> changed = new ArrayList<>();
        String newName = current.name();
        if (name != null && !name.trim().equals(current.name())) {
            newName = name.trim();
            changed.add("name");
        }
        boolean newActive = current.active();
        if (active != null && active != current.active()) {
            newActive = active;
            changed.add("active");
        }
        if (changed.isEmpty()) {
            return view(current);
        }
        if (!repository.updateService(org, id, version, newName, newActive, now)) {
            throw concurrentModification();
        }
        String action = newActive == current.active() ? "SERVICE_UPDATED"
                : newActive ? "SERVICE_ACTIVATED" : "SERVICE_DEACTIVATED";
        audit.record(new AuditEntry(org, actor.userId(), action, "SERVICE", id, AuditOutcome.SUCCESS, changed));
        return view(repository.findService(org, id, now).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PageResult<PriceView> listPrices(OrganizationPrincipal actor, UUID serviceId, int page, int size) {
        checkPage(page, size);
        UUID org = actor.organizationId();
        repository.findService(org, serviceId, now()).orElseThrow(ServiceCatalogService::notFound);
        List<PriceView> items = repository.listPrices(org, serviceId, size, (long) page * size).stream()
                .map(ServiceCatalogService::view).toList();
        return new PageResult<>(items, page, size, repository.countPrices(org, serviceId));
    }

    /**
     * Adds a price version starting at {@code effectiveFrom} (default: now; never in the past). It must start after
     * the service's latest version. Concurrent changes of one service serialize on the service row; the exclusion
     * constraint is the backstop and surfaces as the same {@code 409 PRICE_RANGE_OVERLAP}.
     */
    @Transactional
    public PriceView addPrice(OrganizationPrincipal actor, UUID serviceId, Money amount, Instant effectiveFrom) {
        if (amount.isNegative()) {
            throw new BusinessRuleException("VALIDATION_ERROR", "amount must not be negative.");
        }
        UUID org = actor.organizationId();
        Instant now = now();
        Instant from = effectiveFrom == null ? now : effectiveFrom.truncatedTo(ChronoUnit.MICROS);
        if (from.isBefore(now)) {
            throw new BusinessRuleException("PRICE_EFFECTIVE_IN_PAST",
                    "effectiveFrom must not be in the past; omit it to start now.");
        }
        if (!repository.lockService(org, serviceId)) {
            throw notFound();
        }
        Optional<PriceRow> latest = repository.latestPrice(org, serviceId);
        if (latest.isPresent()) {
            PriceRow previous = latest.get();
            boolean overlaps = !from.isAfter(previous.effectiveFrom())
                    || (previous.effectiveTo() != null && previous.effectiveTo().isAfter(from));
            if (overlaps) {
                throw overlap();
            }
            if (previous.effectiveTo() == null && !repository.closePrice(org, previous.id(), from)) {
                throw overlap();
            }
        }
        UUID id = ids.next();
        if (!repository.insertPrice(id, org, serviceId, amount, from, actor.userId(), now)) {
            throw overlap();
        }
        audit.record(new AuditEntry(org, actor.userId(), "SERVICE_PRICE_CREATED", "SERVICE_PRICE", id,
                AuditOutcome.SUCCESS, List.of("serviceId", "amount", "effectiveFrom")));
        return new PriceView(id, serviceId, amount, from, null, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ServicePrice> priceAt(UUID organizationId, UUID serviceId, Instant at) {
        return repository.priceAt(organizationId, serviceId, at.truncatedTo(ChronoUnit.MICROS))
                .map(p -> new ServicePrice(p.id(), p.serviceId(), p.amount(), p.effectiveFrom(), p.effectiveTo()));
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Service not found.");
    }

    private static ConflictException overlap() {
        return new ConflictException(OVERLAP,
                "A price version of this service already covers that time; a new price must start after the latest one.");
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }

    private static ServiceView view(ServiceRow r) {
        return new ServiceView(r.id(), r.code(), r.name(), r.active(), r.currentPrice(), r.version(), r.createdAt(),
                r.updatedAt());
    }

    private static PriceView view(PriceRow r) {
        return new PriceView(r.id(), r.serviceId(), r.amount(), r.effectiveFrom(), r.effectiveTo(), r.createdAt());
    }
}
