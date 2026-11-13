package com.hms.practitioner.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.UserDirectory;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.practitioner.infrastructure.PersonLinkRepository;
import com.hms.practitioner.infrastructure.StaffRepository;
import com.hms.practitioner.infrastructure.StaffRepository.ListFilter;
import com.hms.practitioner.infrastructure.StaffRepository.StaffRow;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff records ([E4-2]): non-doctor personnel, each linked to exactly one user login (review-notes 1a). A user is
 * at most one doctor or one staff record (V19). Roles stay on the user and are assigned only through the identity
 * module's existing [E1-7] path, so its permission and privilege-escalation checks are never bypassed here.
 */
@Service
public class StaffService {

    static final String ENTITY = "STAFF";
    static final int MAX_PAGE_SIZE = 100;
    static final Set<String> TYPES = Set.of("NURSE", "RECEPTIONIST", "ACCOUNTANT", "LAB_TECHNICIAN", "PHARMACIST",
            "ADMINISTRATIVE", "OTHER");
    private static final String TYPES_TEXT = String.join(", ", TYPES.stream().sorted().toList());
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final StaffRepository repository;
    private final PersonLinkRepository links;
    private final UserDirectory users;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public StaffService(StaffRepository repository, PersonLinkRepository links, UserDirectory users,
            OrganizationDirectory organizations, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.links = links;
        this.users = users;
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public StaffView register(OrganizationPrincipal actor, StaffCommands.Register command) {
        UUID org = actor.organizationId();
        if (command.userId() == null) {
            throw invalid("userId is required.");
        }
        String fullName = fullName(command.fullName());
        String type = staffType(command.staffType());
        String designation = designation(command.designation());
        checkDepartment(org, command.departmentId(), null);

        // Unknown, inactive and other-org users all get the same answer: nothing about another tenant leaks.
        UserDirectory.UserSummary user = users.findForLink(org, command.userId())
                .filter(UserDirectory.UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_AVAILABLE",
                        "userId must be an active user of this organization."));
        links.lock(user.id());
        if (links.linkOf(org, user.id()).isPresent()) {
            throw userTaken();
        }
        UUID id = ids.next();
        repository.insert(id, org, user.id(), fullName, type, designation, command.departmentId(), actor.userId(),
                now());
        List<String> fields = new ArrayList<>(List.of("userId", "fullName", "staffType"));
        if (designation != null) {
            fields.add("designation");
        }
        if (command.departmentId() != null) {
            fields.add("departmentId");
        }
        audit.record(new AuditEntry(org, actor.userId(), "STAFF_CREATED", ENTITY, id, AuditOutcome.SUCCESS, fields));
        return view(repository.find(org, id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public StaffView get(OrganizationPrincipal actor, UUID id) {
        return view(repository.find(actor.organizationId(), id).orElseThrow(StaffService::notFound));
    }

    @Transactional(readOnly = true)
    public PageResult<StaffView> list(OrganizationPrincipal actor, String staffType, UUID departmentId, String q,
            String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status must be ACTIVE or INACTIVE.");
        }
        if (staffType != null) {
            staffType(staffType);
        }
        UUID org = actor.organizationId();
        String term = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        String like = term == null ? null
                : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        ListFilter filter = new ListFilter(staffType, departmentId, like, status);
        List<StaffView> items = repository.list(org, filter, size, (long) page * size).stream()
                .map(StaffService::view).toList();
        return new PageResult<>(items, page, size, repository.count(org, filter));
    }

    @Transactional
    public StaffView update(OrganizationPrincipal actor, UUID id, StaffCommands.Update command) {
        UUID org = actor.organizationId();
        StaffRow current = repository.lock(org, id).orElseThrow(StaffService::notFound);
        if (command.version() == null) {
            throw invalid("version is required.");
        }
        if (command.version() != current.version()) {
            throw concurrentModification();
        }
        List<String> changed = new ArrayList<>();
        String fullName = current.fullName();
        if (command.fullName() != null) {
            fullName = fullName(command.fullName());
            track(changed, "fullName", current.fullName(), fullName);
        }
        String type = current.staffType();
        if (command.staffType() != null) {
            type = staffType(command.staffType());
            track(changed, "staffType", current.staffType(), type);
        }
        String designation = current.designation();
        if (command.designation() != null) {
            designation = designation(command.designation());
            track(changed, "designation", current.designation(), designation);
        }
        if (command.clearDepartment() && command.departmentId() != null) {
            throw invalid("give either departmentId or clearDepartment, not both.");
        }
        UUID department = current.departmentId();
        if (command.clearDepartment()) {
            department = null;
        } else if (command.departmentId() != null) {
            checkDepartment(org, command.departmentId(), current.departmentId());
            department = command.departmentId();
        }
        track(changed, "departmentId", current.departmentId(), department);
        String status = current.status();
        if (command.status() != null) {
            if (!STATUSES.contains(command.status())) {
                throw invalid("status must be ACTIVE or INACTIVE.");
            }
            status = command.status();
            track(changed, "status", current.status(), status);
        }
        if (changed.isEmpty()) {
            return view(current);
        }
        if (!repository.update(org, id, command.version(), fullName, type, designation, department, status, now())) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "STAFF_UPDATED", ENTITY, id, AuditOutcome.SUCCESS, changed));
        return view(repository.find(org, id).orElseThrow());
    }

    // --- validation -------------------------------------------------------------------------------------------

    /** Unknown and other-org departments get the same 422; an inactive one only if it is already assigned. */
    private void checkDepartment(UUID org, UUID departmentId, UUID alreadyAssigned) {
        if (departmentId == null) {
            return;
        }
        Boolean active = organizations.departmentActiveFlags(org, List.of(departmentId)).get(departmentId);
        if (active == null || (!active && !departmentId.equals(alreadyAssigned))) {
            throw new BusinessRuleException("DEPARTMENT_NOT_AVAILABLE",
                    "departmentId must be an active department of this organization.");
        }
    }

    private static String fullName(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() < 2 || v.length() > 120) {
            throw invalid("fullName must be 2 to 120 characters.");
        }
        return v;
    }

    private static String staffType(String value) {
        if (value == null || !TYPES.contains(value)) {
            throw invalid("staffType must be one of " + TYPES_TEXT + ".");
        }
        return value;
    }

    /** Empty clears it. */
    private static String designation(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > 80) {
            throw invalid("designation must be at most 80 characters.");
        }
        return v;
    }

    private static void track(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    private static StaffView view(StaffRow r) {
        return new StaffView(r.id(), r.userId(), r.fullName(), r.staffType(), r.designation(), r.departmentId(),
                r.status(), r.version(), r.createdAt(), r.updatedAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Shared with doctor registration: a user is at most one doctor or one staff record. */
    static ConflictException userTaken() {
        return new ConflictException("USER_ALREADY_LINKED",
                "This user is already linked to a doctor or staff record.");
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Staff record not found.");
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }
}
