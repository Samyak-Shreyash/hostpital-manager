package com.hms.bedmanagement.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.infrastructure.WardRepository;
import com.hms.bedmanagement.infrastructure.WardRepository.WardRow;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wards ([E8-1]). Never deleted: a ward is retired with {@code status=INACTIVE}. */
@Service
public class WardService {

    static final int MAX_PAGE_SIZE = 100;
    static final String ENTITY = "WARD";
    static final String GENERAL = "GENERAL";

    private final WardRepository wards;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public WardService(WardRepository wards, OrganizationDirectory organizations, AuditRecorder audit,
            UuidV7Generator ids, Clock clock) {
        this.wards = wards;
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional(readOnly = true)
    public PageResult<WardView> list(OrganizationPrincipal actor, String status, int page, int size) {
        checkPage(page, size);
        UUID org = actor.organizationId();
        List<WardView> items = wards.list(org, status, size, (long) page * size).stream().map(WardService::view)
                .toList();
        return new PageResult<>(items, page, size, wards.count(org, status));
    }

    @Transactional
    public WardView create(OrganizationPrincipal actor, String code, String name, UUID departmentId,
            String careType) {
        UUID org = actor.organizationId();
        checkDepartment(org, departmentId);
        UUID id = ids.next();
        Instant now = now();
        if (!wards.insert(id, org, code, name.trim(), departmentId,
                careType == null ? GENERAL : careType, now)) {
            throw new ConflictException("DUPLICATE_WARD_CODE", "Ward code already exists.");
        }
        audit.record(new AuditEntry(org, actor.userId(), "WARD_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("code", "name", "departmentId", "status", "careType")));
        return view(wards.find(org, id).orElseThrow());
    }

    /** Partial update; {@code version} must match. {@code null} leaves a field alone. Code is immutable. */
    @Transactional
    public WardView update(OrganizationPrincipal actor, UUID id, long version, String name, UUID departmentId,
            String status, String careType) {
        if (name == null && departmentId == null && status == null && careType == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide name, departmentId, status and/or careType.");
        }
        UUID org = actor.organizationId();
        WardRow current = wards.find(org, id).orElseThrow(WardService::notFound);
        if (current.version() != version) {
            throw concurrentModification();
        }
        List<String> changed = new ArrayList<>();
        String newName = current.name();
        if (name != null && !name.trim().equals(current.name())) {
            newName = name.trim();
            changed.add("name");
        }
        UUID newDepartment = current.departmentId();
        if (departmentId != null && !departmentId.equals(current.departmentId())) {
            checkDepartment(org, departmentId);
            newDepartment = departmentId;
            changed.add("departmentId");
        }
        String newStatus = current.status();
        if (status != null && !status.equals(current.status())) {
            newStatus = status;
            changed.add("status");
        }
        String newCareType = current.careType();
        if (careType != null && !careType.equals(current.careType())) {
            newCareType = careType;
            changed.add("careType");
        }
        if (changed.isEmpty()) {
            return view(current);
        }
        if (!wards.update(org, id, version, newName, newDepartment, newStatus, newCareType, now())) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "WARD_UPDATED", ENTITY, id, AuditOutcome.SUCCESS, changed));
        return view(wards.find(org, id).orElseThrow());
    }

    private void checkDepartment(UUID org, UUID departmentId) {
        if (departmentId != null
                && organizations.departmentActiveFlags(org, List.of(departmentId)).get(departmentId) == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "departmentId does not exist.");
        }
    }

    static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("Ward not found.");
    }

    static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }

    private static WardView view(WardRow r) {
        return new WardView(r.id(), r.code(), r.name(), r.departmentId(), r.status(), r.careType(), r.version(),
                r.createdAt(), r.updatedAt());
    }
}
