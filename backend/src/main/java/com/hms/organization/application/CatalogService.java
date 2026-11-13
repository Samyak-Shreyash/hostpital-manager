package com.hms.organization.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.infrastructure.CatalogRepository;
import com.hms.organization.infrastructure.CatalogRepository.Row;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Departments and specialties (api-design.md section 4). Never deleted: {@code active=false} retires an entry so
 * later references (doctors, wards) stay valid. The code is fixed at creation; only name and active change.
 */
@Service
public class CatalogService {

    static final int MAX_PAGE_SIZE = 100;

    private final CatalogRepository repository;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public CatalogService(CatalogRepository repository, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<CatalogItemView> list(OrganizationPrincipal actor, CatalogKind kind, Boolean active, int page,
            int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        UUID org = actor.organizationId();
        List<CatalogItemView> items = repository.list(kind, org, active, size, (long) page * size).stream()
                .map(CatalogService::view).toList();
        return new PageResult<>(items, page, size, repository.count(kind, org, active));
    }

    @Transactional
    public CatalogItemView create(OrganizationPrincipal actor, CatalogKind kind, String code, String name) {
        UUID org = actor.organizationId();
        UUID id = ids.next();
        if (!repository.insertIfAbsent(kind, id, org, code, name.trim(), clock.instant())) {
            throw new ConflictException(kind.duplicateCode(), kind.label() + " code already exists.");
        }
        audit.record(new AuditEntry(org, actor.userId(), kind.entity() + "_CREATED", kind.entity(), id,
                AuditOutcome.SUCCESS, List.of("code", "name", "active")));
        return view(repository.find(kind, org, id).orElseThrow());
    }

    /** Partial update; {@code version} must match. {@code null} leaves a field alone. */
    @Transactional
    public CatalogItemView update(OrganizationPrincipal actor, CatalogKind kind, UUID id, long version, String name,
            Boolean active) {
        if (name == null && active == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide name and/or active.");
        }
        UUID org = actor.organizationId();
        Row current = repository.find(kind, org, id)
                .orElseThrow(() -> new NotFoundException(kind.label() + " not found."));
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
        if (!repository.update(kind, org, id, version, newName, newActive, clock.instant())) {
            throw concurrentModification();
        }
        String action = newActive == current.active() ? "_UPDATED" : newActive ? "_ACTIVATED" : "_DEACTIVATED";
        audit.record(new AuditEntry(org, actor.userId(), kind.entity() + action, kind.entity(), id,
                AuditOutcome.SUCCESS, changed));
        return view(repository.find(kind, org, id).orElseThrow());
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }

    private static CatalogItemView view(Row r) {
        return new CatalogItemView(r.id(), r.code(), r.name(), r.active(), r.version(), r.createdAt(),
                r.updatedAt());
    }
}
