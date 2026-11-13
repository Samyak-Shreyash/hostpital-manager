package com.hms.practitioner.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.UserDirectory;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.practitioner.application.PractitionerCommands.Register;
import com.hms.practitioner.application.PractitionerCommands.ReplaceDepartments;
import com.hms.practitioner.application.PractitionerCommands.ReplaceSpecialties;
import com.hms.practitioner.application.PractitionerCommands.Update;
import com.hms.practitioner.infrastructure.PractitionerRepository;
import com.hms.practitioner.infrastructure.PractitionerRepository.ListFilter;
import com.hms.practitioner.infrastructure.PractitionerRepository.PractitionerRow;
import com.hms.practitioner.infrastructure.PractitionerRepository.SpecialtyLink;
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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Doctor registration ([E4-1], PRC-1, PRC-2): profile, linked user, specialties (exactly one primary) and departments.
 *
 * <p>Integrity is enforced by the database (V18): same-org composite FKs to user, specialty and department; one
 * practitioner per user; registration number unique per organization; at most one primary (partial unique index) and
 * exactly one at commit (deferred constraint trigger). The checks here run first only to give clear errors. Every
 * change locks the practitioner row and compares {@code version}, so concurrent edits serialize or get 409.
 */
@Service
public class PractitionerService {

    static final String ENTITY = "PRACTITIONER";
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_LINKS = 20;
    static final int MAX_QUALIFICATIONS = 10;
    static final int DEFAULT_SLOT_MINUTES = 15;
    private static final Pattern REGISTRATION_NO = Pattern.compile("[A-Z0-9][A-Z0-9/.-]{0,49}");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final PractitionerRepository repository;
    private final UserDirectory users;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public PractitionerService(PractitionerRepository repository, UserDirectory users,
            OrganizationDirectory organizations, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.users = users;
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public PractitionerView register(OrganizationPrincipal actor, Register command) {
        UUID org = actor.organizationId();
        if (command.userId() == null) {
            throw invalid("userId is required.");
        }
        String fullName = fullName(command.fullName());
        String registrationNo = registrationNo(command.registrationNo());
        String council = council(command.registrationCouncil());
        List<String> qualifications = qualifications(command.qualifications());
        int slot = slot(command.defaultSlotMinutes() == null ? DEFAULT_SLOT_MINUTES : command.defaultSlotMinutes());
        List<SpecialtyLink> specialties = specialtyLinks(command.specialtyIds(), command.primarySpecialtyId());
        List<UUID> departments = distinctIds(command.departmentIds(), "departmentIds", false);

        // Unknown, inactive and other-org users all get the same answer: nothing about another tenant leaks.
        UserDirectory.UserSummary user = users.findForLink(org, command.userId())
                .filter(UserDirectory.UserSummary::active)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_AVAILABLE",
                        "userId must be an active user of this organization."));
        if (repository.userLinked(org, user.id())) {
            throw new ConflictException("PRACTITIONER_USER_TAKEN", "This user is already linked to a doctor.");
        }
        checkCatalog(org, specialties.stream().map(SpecialtyLink::specialtyId).toList(), Set.of(),
                organizations::specialtyActiveFlags, "SPECIALTY_NOT_AVAILABLE", "specialtyIds");
        checkCatalog(org, departments, Set.of(), organizations::departmentActiveFlags, "DEPARTMENT_NOT_AVAILABLE",
                "departmentIds");

        UUID id = ids.next();
        Instant now = now();
        if (!repository.insertIfAbsent(id, org, user.id(), fullName, registrationNo, council, qualifications, slot,
                actor.userId(), now)) {
            // A unique index refused the row (possibly a concurrent registration that has now committed).
            if (repository.userLinked(org, user.id())) {
                throw new ConflictException("PRACTITIONER_USER_TAKEN", "This user is already linked to a doctor.");
            }
            throw duplicateRegistration();
        }
        repository.replaceSpecialties(org, id, specialties, now);
        repository.replaceDepartments(org, id, departments, now);
        List<String> fields = new ArrayList<>(List.of("userId", "fullName", "registrationNo", "defaultSlotMinutes",
                "specialtyIds", "primarySpecialtyId"));
        if (council != null) {
            fields.add("registrationCouncil");
        }
        if (!qualifications.isEmpty()) {
            fields.add("qualifications");
        }
        if (!departments.isEmpty()) {
            fields.add("departmentIds");
        }
        audit.record(new AuditEntry(org, actor.userId(), "PRACTITIONER_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                fields));
        return view(org, repository.find(org, id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public PractitionerView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        return view(org, repository.find(org, id).orElseThrow(PractitionerService::notFound));
    }

    @Transactional(readOnly = true)
    public PageResult<PractitionerView> list(OrganizationPrincipal actor, UUID specialtyId, UUID departmentId,
            String q, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status must be ACTIVE or INACTIVE.");
        }
        UUID org = actor.organizationId();
        String term = q == null || q.isBlank() ? null : q.trim().toLowerCase(Locale.ROOT);
        String like = term == null ? null
                : "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        ListFilter filter = new ListFilter(specialtyId, departmentId, like, status);
        List<PractitionerView> items = repository.list(org, filter, size, (long) page * size).stream()
                .map(r -> view(org, r)).toList();
        return new PageResult<>(items, page, size, repository.count(org, filter));
    }

    @Transactional
    public PractitionerView update(OrganizationPrincipal actor, UUID id, Update command) {
        UUID org = actor.organizationId();
        PractitionerRow current = repository.lock(org, id).orElseThrow(PractitionerService::notFound);
        long version = requireVersion(command.version(), current);
        List<String> changed = new ArrayList<>();
        String fullName = current.fullName();
        if (command.fullName() != null) {
            fullName = fullName(command.fullName());
            track(changed, "fullName", current.fullName(), fullName);
        }
        String registrationNo = current.registrationNo();
        if (command.registrationNo() != null) {
            registrationNo = registrationNo(command.registrationNo());
            track(changed, "registrationNo", current.registrationNo(), registrationNo);
        }
        String council = current.registrationCouncil();
        if (command.registrationCouncil() != null) {
            council = council(command.registrationCouncil());
            track(changed, "registrationCouncil", current.registrationCouncil(), council);
        }
        List<String> qualifications = current.qualifications();
        if (command.qualifications() != null) {
            qualifications = qualifications(command.qualifications());
            track(changed, "qualifications", current.qualifications(), qualifications);
        }
        int slot = current.defaultSlotMinutes();
        if (command.defaultSlotMinutes() != null) {
            slot = slot(command.defaultSlotMinutes());
            track(changed, "defaultSlotMinutes", current.defaultSlotMinutes(), slot);
        }
        String status = current.status();
        if (command.status() != null) {
            if (!STATUSES.contains(command.status())) {
                throw invalid("status must be ACTIVE or INACTIVE.");
            }
            status = command.status();
            track(changed, "status", current.status(), status);
        }
        if (changed.isEmpty()) {
            return view(org, current);
        }
        if (changed.contains("registrationNo") && repository.registrationTaken(org, registrationNo, id)) {
            throw duplicateRegistration();
        }
        boolean updated;
        try {
            updated = repository.update(org, id, version, fullName, registrationNo, council, qualifications, slot,
                    status, now());
        } catch (DuplicateKeyException e) {
            // A concurrent edit of another doctor took the number after the check; the transaction rolls back.
            throw duplicateRegistration();
        }
        if (!updated) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "PRACTITIONER_UPDATED", ENTITY, id, AuditOutcome.SUCCESS,
                changed));
        return view(org, repository.find(org, id).orElseThrow());
    }

    @Transactional
    public PractitionerView replaceSpecialties(OrganizationPrincipal actor, UUID id, ReplaceSpecialties command) {
        UUID org = actor.organizationId();
        PractitionerRow current = repository.lock(org, id).orElseThrow(PractitionerService::notFound);
        long version = requireVersion(command.version(), current);
        List<SpecialtyLink> links = specialtyLinks(command.specialtyIds(), command.primarySpecialtyId());
        List<SpecialtyLink> existing = repository.specialties(org, id);
        Set<UUID> assigned = new HashSet<>();
        existing.forEach(l -> assigned.add(l.specialtyId()));
        checkCatalog(org, links.stream().map(SpecialtyLink::specialtyId).toList(), assigned,
                organizations::specialtyActiveFlags, "SPECIALTY_NOT_AVAILABLE", "specialtyIds");
        if (new HashSet<>(links).equals(new HashSet<>(existing))) {
            return view(org, current);
        }
        Instant now = now();
        if (!repository.touch(org, id, version, now)) {
            throw concurrentModification();
        }
        repository.replaceSpecialties(org, id, links, now);
        List<String> changed = new ArrayList<>();
        Set<UUID> requested = new HashSet<>(links.stream().map(SpecialtyLink::specialtyId).toList());
        if (!requested.equals(assigned)) {
            changed.add("specialtyIds");
        }
        if (!Objects.equals(primaryOf(links), primaryOf(existing))) {
            changed.add("primarySpecialtyId");
        }
        audit.record(new AuditEntry(org, actor.userId(), "PRACTITIONER_SPECIALTIES_CHANGED", ENTITY, id,
                AuditOutcome.SUCCESS, changed));
        return view(org, repository.find(org, id).orElseThrow());
    }

    @Transactional
    public PractitionerView replaceDepartments(OrganizationPrincipal actor, UUID id, ReplaceDepartments command) {
        UUID org = actor.organizationId();
        PractitionerRow current = repository.lock(org, id).orElseThrow(PractitionerService::notFound);
        long version = requireVersion(command.version(), current);
        if (command.departmentIds() == null) {
            throw invalid("departmentIds is required (an empty list removes all departments).");
        }
        List<UUID> departments = distinctIds(command.departmentIds(), "departmentIds", false);
        Set<UUID> assigned = new HashSet<>(repository.departments(org, id));
        checkCatalog(org, departments, assigned, organizations::departmentActiveFlags, "DEPARTMENT_NOT_AVAILABLE",
                "departmentIds");
        if (new HashSet<>(departments).equals(assigned)) {
            return view(org, current);
        }
        Instant now = now();
        if (!repository.touch(org, id, version, now)) {
            throw concurrentModification();
        }
        repository.replaceDepartments(org, id, departments, now);
        audit.record(new AuditEntry(org, actor.userId(), "PRACTITIONER_DEPARTMENTS_CHANGED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("departmentIds")));
        return view(org, repository.find(org, id).orElseThrow());
    }

    // --- validation -------------------------------------------------------------------------------------------

    private static String fullName(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() < 2 || v.length() > 120) {
            throw invalid("fullName must be 2 to 120 characters.");
        }
        return v;
    }

    /** Trimmed and upper-cased, so "tsmc-1" and "TSMC-1" are the same registration. */
    private static String registrationNo(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!REGISTRATION_NO.matcher(v).matches()) {
            throw invalid("registrationNo must be 1 to 50 letters, digits, '/', '.' or '-'.");
        }
        return v;
    }

    /** Empty clears it. */
    private static String council(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (v.length() > 100) {
            throw invalid("registrationCouncil must be at most 100 characters.");
        }
        return v;
    }

    private static List<String> qualifications(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String v = value == null ? "" : value.trim();
            if (v.isEmpty() || v.length() > 50) {
                throw invalid("each qualification must be 1 to 50 characters.");
            }
            result.add(v);
        }
        if (result.size() > MAX_QUALIFICATIONS) {
            throw invalid("at most " + MAX_QUALIFICATIONS + " qualifications.");
        }
        return List.copyOf(result);
    }

    private static int slot(int minutes) {
        if (minutes < 5 || minutes > 120) {
            throw invalid("defaultSlotMinutes must be 5 to 120.");
        }
        return minutes;
    }

    /** Exactly one primary, and it must be one of the specialties. */
    private static List<SpecialtyLink> specialtyLinks(List<UUID> specialtyIds, UUID primary) {
        List<UUID> distinct = distinctIds(specialtyIds, "specialtyIds", true);
        if (primary == null) {
            throw invalid("primarySpecialtyId is required: a doctor has exactly one primary specialty.");
        }
        if (!distinct.contains(primary)) {
            throw invalid("primarySpecialtyId must be one of specialtyIds.");
        }
        return distinct.stream().map(s -> new SpecialtyLink(s, s.equals(primary))).toList();
    }

    private static List<UUID> distinctIds(List<UUID> values, String field, boolean required) {
        List<UUID> list = values == null ? List.of() : values;
        if (required && list.isEmpty()) {
            throw invalid(field + " must contain at least one id.");
        }
        if (list.stream().anyMatch(Objects::isNull)) {
            throw invalid(field + " must not contain nulls.");
        }
        List<UUID> distinct = List.copyOf(new LinkedHashSet<>(list));
        if (distinct.size() != list.size()) {
            throw invalid(field + " must not contain duplicates.");
        }
        if (distinct.size() > MAX_LINKS) {
            throw invalid(field + " must contain at most " + MAX_LINKS + " ids.");
        }
        return distinct;
    }

    /**
     * Every id must be an entry of the organization; an inactive one is accepted only if already assigned (so an
     * unrelated edit does not force dropping it). Unknown and other-org ids get the same 422.
     */
    private static void checkCatalog(UUID org, List<UUID> requested, Set<UUID> alreadyAssigned,
            BiFunction<UUID, Collection<UUID>, Map<UUID, Boolean>> lookup, String code, String field) {
        if (requested.isEmpty()) {
            return;
        }
        Map<UUID, Boolean> active = lookup.apply(org, requested);
        for (UUID id : requested) {
            Boolean flag = active.get(id);
            if (flag == null || (!flag && !alreadyAssigned.contains(id))) {
                throw new BusinessRuleException(code, field + " must be active entries of this organization.");
            }
        }
    }

    private static long requireVersion(Long version, PractitionerRow current) {
        if (version == null) {
            throw invalid("version is required.");
        }
        if (version != current.version()) {
            throw concurrentModification();
        }
        return version;
    }

    private static void track(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    private static UUID primaryOf(List<SpecialtyLink> links) {
        return links.stream().filter(SpecialtyLink::primary).map(SpecialtyLink::specialtyId).findFirst().orElse(null);
    }

    private PractitionerView view(UUID org, PractitionerRow r) {
        List<SpecialtyLink> specialties = repository.specialties(org, r.id());
        return new PractitionerView(r.id(), r.userId(), r.fullName(), r.registrationNo(), r.registrationCouncil(),
                r.qualifications(), r.defaultSlotMinutes(), r.status(), primaryOf(specialties),
                specialties.stream().map(SpecialtyLink::specialtyId).toList(), repository.departments(org, r.id()),
                r.version(), r.createdAt(), r.updatedAt());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Doctor not found.");
    }

    private static ConflictException duplicateRegistration() {
        return new ConflictException("DUPLICATE_REGISTRATION_NO",
                "Another doctor of this organization already has this registration number.");
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }
}
