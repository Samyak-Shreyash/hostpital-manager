package com.hms.identity.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.domain.PasswordPolicy;
import com.hms.identity.domain.TemporaryPassword;
import com.hms.identity.infrastructure.AppUserEntity;
import com.hms.identity.infrastructure.AppUserRepository;
import com.hms.identity.infrastructure.RbacRepository;
import com.hms.identity.infrastructure.UserAdminRepository;
import com.hms.identity.infrastructure.UserAdminRepository.UserRow;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User management (api-design.md section 3, PRD IAM). Every method acts in the organization of the authenticated
 * principal; another organization's user is a {@code NotFoundException}. {@code app_user} is outside RLS, so each
 * query filters by organization explicitly.
 *
 * <p>Rules: nobody can deactivate their own account, and no change may leave an organization without an active
 * administrator (see {@link RbacRepository#countActiveAdministrators}); changes that could remove one first lock
 * the organization row, so two concurrent requests cannot both pass the check. Deactivation, a role change and a
 * password reset end the user's sessions (a role change otherwise only applies at next login, ADR-0006).
 * The temporary password is returned once and never logged or audited. Audit rows carry field names only.
 */
@Service
public class UserManagementService {

    static final String ENTITY = "APP_USER";
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private final AppUserRepository users;
    private final UserAdminRepository userAdmin;
    private final RbacRepository rbac;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public UserManagementService(AppUserRepository users, UserAdminRepository userAdmin, RbacRepository rbac,
            PasswordEncoder passwordEncoder, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.users = users;
        this.userAdmin = userAdmin;
        this.rbac = rbac;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResult<UserView> list(AuthenticatedPrincipal actor, String status, String roleCode, String query,
            int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        String normalisedStatus = blankToNull(status) == null ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalisedStatus != null && !STATUSES.contains(normalisedStatus)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "status must be ACTIVE or INACTIVE.");
        }
        UUID org = actor.organizationId();
        String like = blankToNull(query) == null ? null : likePattern(query.trim());
        String role = blankToNull(roleCode);
        List<UserRow> rows = userAdmin.list(org, normalisedStatus, role, like, size, (long) page * size);
        Map<UUID, List<String>> roles = rbac.findRoleCodesByUsers(org, rows.stream().map(UserRow::id).toList());
        List<UserView> items = rows.stream().map(r -> new UserView(r.id(), r.email(), r.fullName(), r.status(),
                r.mustChangePassword(), roles.getOrDefault(r.id(), List.of()), r.version(), r.createdAt(),
                r.updatedAt())).toList();
        return new PageResult<>(items, page, size, userAdmin.count(org, normalisedStatus, role, like));
    }

    /** Detail view; audited because it exposes an account's identity and access. */
    @Transactional
    public UserView get(AuthenticatedPrincipal actor, UUID userId) {
        AppUserEntity user = find(actor, userId);
        audit.record(AuditEntry.of(actor.organizationId(), actor.userId(), "USER_VIEWED", ENTITY, user.getId(),
                AuditOutcome.SUCCESS));
        return view(user);
    }

    @Transactional
    public UserWithTemporaryPassword create(AuthenticatedPrincipal actor, String email, String fullName,
            List<String> roleCodes) {
        String temporaryPassword = TemporaryPassword.generate();
        UserView view = insertUser(actor.organizationId(), actor.userId(), email, fullName, roleCodes,
                temporaryPassword);
        return new UserWithTemporaryPassword(view, temporaryPassword);
    }

    /**
     * First-run bootstrap: the System Administrator of a freshly created organization, with the given temporary
     * password (the caller delivers it out of band and never logs it). Must run in the transaction that
     * provisioned the organization's roles. Audited as the system (no actor); the password is not part of the audit.
     */
    @Transactional
    public UserView createInitialAdministrator(UUID organizationId, String email, String fullName,
            String temporaryPassword) {
        return insertUser(organizationId, null, email, fullName, List.of("SYSTEM_ADMIN"), temporaryPassword);
    }

    private UserView insertUser(UUID org, UUID actorId, String email, String fullName, List<String> roleCodes,
            String temporaryPassword) {
        String normalisedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmail(normalisedEmail)) {
            throw duplicateEmail();
        }
        Map<String, UUID> roleIds = resolveRoles(org, roleCodes);
        PasswordPolicy.require(temporaryPassword, normalisedEmail);
        Instant now = clock.instant();
        AppUserEntity user = AppUserEntity.create(ids.next(), org, normalisedEmail, fullName.trim(),
                passwordEncoder.encode(temporaryPassword), now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with another create of the same email: the unique index is the real guard.
            throw duplicateEmail();
        }
        roleIds.values().forEach(roleId -> rbac.insertUserRole(org, user.getId(), roleId));
        audit.record(new AuditEntry(org, actorId, "USER_CREATED", ENTITY, user.getId(), AuditOutcome.SUCCESS,
                List.of("email", "fullName", "mustChangePassword", "roles", "status")));
        return view(user, List.copyOf(new TreeSet<>(roleIds.keySet())));
    }

    /**
     * Updates the name and/or replaces the role set. {@code version} must match (optimistic locking). A role change
     * ends the user's sessions so it takes effect at once; {@code currentSessionId} is kept when the caller edits
     * their own account.
     */
    @Transactional
    public UserView update(AuthenticatedPrincipal actor, UUID userId, long version, String fullName,
            List<String> roleCodes, String currentSessionId) {
        if (fullName == null && roleCodes == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide fullName and/or roleCodes.");
        }
        UUID org = actor.organizationId();
        rbac.lockOrganization(org);
        AppUserEntity user = findForUpdate(actor, userId);
        requireVersion(user.getVersion(), version);
        Instant now = clock.instant();
        List<String> changed = new ArrayList<>();
        if (fullName != null && !fullName.trim().equals(user.getFullName())) {
            user.rename(fullName.trim(), now);
            changed.add("fullName");
        }
        boolean rolesChanged = false;
        if (roleCodes != null) {
            int adminsBefore = rbac.countActiveAdministrators(org);
            rolesChanged = replaceRoles(org, user.getId(), roleCodes);
            if (rolesChanged) {
                changed.add("roles");
                user.touch(now);
                requireAdministratorRemains(org, adminsBefore);
            }
        }
        users.saveAndFlush(user);
        if (rolesChanged) {
            userAdmin.deleteSessions(user.getId(), keepFor(actor, user.getId(), currentSessionId));
        }
        audit.record(new AuditEntry(org, actor.userId(), "USER_UPDATED", ENTITY, user.getId(), AuditOutcome.SUCCESS,
                changed));
        return view(user);
    }

    /** Idempotent: setting the status a user already has changes nothing and is not audited. */
    @Transactional
    public UserView setActive(AuthenticatedPrincipal actor, UUID userId, boolean active) {
        if (!active && actor.userId().equals(userId)) {
            throw new ConflictException("CANNOT_DEACTIVATE_SELF", "You cannot deactivate your own account.");
        }
        UUID org = actor.organizationId();
        rbac.lockOrganization(org);
        AppUserEntity user = findForUpdate(actor, userId);
        if (user.isActive() == active) {
            return view(user);
        }
        int adminsBefore = rbac.countActiveAdministrators(org);
        user.setActive(active, clock.instant());
        users.saveAndFlush(user); // the administrator count below reads the row, so it must be written first
        if (!active) {
            requireAdministratorRemains(org, adminsBefore);
            userAdmin.deleteSessions(user.getId(), null);
        }
        audit.record(new AuditEntry(org, actor.userId(), active ? "USER_ACTIVATED" : "USER_DEACTIVATED", ENTITY,
                user.getId(), AuditOutcome.SUCCESS, List.of("status")));
        return view(user);
    }

    /**
     * Admin password reset: a new temporary password the user must change, lockout cleared, the user's other
     * sessions ended ({@code currentSessionId} is kept when the caller resets their own password).
     */
    @Transactional
    public UserWithTemporaryPassword resetPassword(AuthenticatedPrincipal actor, UUID userId,
            String currentSessionId) {
        AppUserEntity user = findForUpdate(actor, userId);
        String temporaryPassword = TemporaryPassword.generate();
        PasswordPolicy.require(temporaryPassword, user.getEmail());
        user.resetPassword(passwordEncoder.encode(temporaryPassword), clock.instant());
        users.saveAndFlush(user);
        userAdmin.deleteSessions(user.getId(), keepFor(actor, user.getId(), currentSessionId));
        audit.record(new AuditEntry(actor.organizationId(), actor.userId(), "USER_PASSWORD_RESET", ENTITY,
                user.getId(), AuditOutcome.SUCCESS, List.of("mustChangePassword", "passwordHash")));
        return new UserWithTemporaryPassword(view(user), temporaryPassword);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private AppUserEntity find(AuthenticatedPrincipal actor, UUID userId) {
        return users.findByIdAndOrganization(userId, actor.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    private AppUserEntity findForUpdate(AuthenticatedPrincipal actor, UUID userId) {
        return users.findByIdAndOrganizationForUpdate(userId, actor.organizationId())
                .orElseThrow(() -> new NotFoundException("User not found."));
    }

    private UserView view(AppUserEntity user) {
        return view(user, rbac.findRoleCodes(user.getOrganizationId(), user.getId()));
    }

    private static UserView view(AppUserEntity user, List<String> roleCodes) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getStatus(),
                user.isMustChangePassword(), roleCodes, user.getVersion(), user.getCreatedAt(), user.getUpdatedAt());
    }

    /** Codes must all exist in the caller's organization; another organization's role is simply unknown. */
    private Map<String, UUID> resolveRoles(UUID org, List<String> roleCodes) {
        Set<String> wanted = new LinkedHashSet<>(roleCodes);
        Map<String, UUID> found = rbac.findRoleIdsByCodes(org, wanted);
        if (found.size() != wanted.size()) {
            List<String> unknown = wanted.stream().filter(code -> !found.containsKey(code)).sorted().toList();
            throw new BusinessRuleException("UNKNOWN_ROLE",
                    "Unknown role code(s): " + String.join(", ", unknown) + ".");
        }
        return found;
    }

    /** Applies the difference between the current and wanted role sets; returns whether anything changed. */
    private boolean replaceRoles(UUID org, UUID userId, List<String> wantedCodes) {
        Map<String, UUID> wanted = resolveRoles(org, wantedCodes);
        Map<String, UUID> current = rbac.findRoleIdsByCodes(org, new LinkedHashSet<>(rbac.findRoleCodes(org, userId)));
        boolean changed = false;
        for (Map.Entry<String, UUID> role : current.entrySet()) {
            if (!wanted.containsKey(role.getKey())) {
                rbac.deleteUserRole(org, userId, role.getValue());
                changed = true;
            }
        }
        for (Map.Entry<String, UUID> role : wanted.entrySet()) {
            if (!current.containsKey(role.getKey())) {
                rbac.insertUserRole(org, userId, role.getValue());
                changed = true;
            }
        }
        return changed;
    }

    /** The change is already applied in this transaction; an exception rolls it back. */
    private void requireAdministratorRemains(UUID org, int adminsBefore) {
        if (adminsBefore > 0 && rbac.countActiveAdministrators(org) == 0) {
            throw new ConflictException("LAST_ADMINISTRATOR",
                    "This would leave the organization without an active system administrator.");
        }
    }

    private static void requireVersion(long actual, long expected) {
        if (actual != expected) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
    }

    /** The caller's own session survives a change to their own account; everyone else's sessions all end. */
    private static String keepFor(AuthenticatedPrincipal actor, UUID targetUserId, String currentSessionId) {
        return actor.userId().equals(targetUserId) ? currentSessionId : null;
    }

    private static ConflictException duplicateEmail() {
        return new ConflictException("DUPLICATE_EMAIL", "A user with this email already exists.");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Lower-cased substring pattern with LIKE wildcards escaped (the query uses ESCAPE backslash). */
    static String likePattern(String query) {
        String escaped = query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
