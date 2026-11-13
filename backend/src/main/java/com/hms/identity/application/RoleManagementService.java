package com.hms.identity.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.api.Permissions;
import com.hms.identity.infrastructure.RbacRepository;
import com.hms.identity.infrastructure.RbacRepository.RoleRow;
import com.hms.identity.infrastructure.UserAdminRepository;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.web.ProblemException;
import org.springframework.http.HttpStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Role reference data and custom role management (api-design.md section 3, ADR-0006). Roles are organization-owned
 * data and are never deleted. A change to a role's permissions ends the sessions of everyone who holds it, so the
 * change applies at once, and may not leave the organization without an active administrator (the same rule and
 * the same organization lock as {@link UserManagementService}).
 */
@Service
public class RoleManagementService {

    static final String ENTITY = "ROLE";

    private final RbacRepository rbac;
    private final UserAdminRepository userAdmin;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public RoleManagementService(RbacRepository rbac, UserAdminRepository userAdmin, AuditRecorder audit,
            UuidV7Generator ids, Clock clock) {
        this.rbac = rbac;
        this.userAdmin = userAdmin;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RoleView> list(AuthenticatedPrincipal actor) {
        UUID org = actor.organizationId();
        List<RoleRow> roles = rbac.findRoles(org);
        Map<UUID, List<String>> permissions = rbac.findPermissionCodesByRoles(org, roles.stream()
                .map(RoleRow::id).toList());
        return roles.stream().map(role -> view(role, permissions.getOrDefault(role.id(), List.of()))).toList();
    }

    /** The code-defined catalogue (identical to the {@code permission} table, which a test compares). */
    public List<String> permissions() {
        return Permissions.ALL.stream().sorted().toList();
    }

    @Transactional
    public RoleView create(AuthenticatedPrincipal actor, String code, String name, List<String> permissionCodes) {
        UUID org = actor.organizationId();
        Set<String> permissions = requireKnownPermissions(permissionCodes);
        requireCallerHolds(actor, permissions);
        UUID id = ids.next();
        Instant now = clock.instant();
        if (!rbac.insertRoleIfAbsent(id, org, code, name.trim(), now)) {
            throw new ConflictException("DUPLICATE_ROLE_CODE", "A role with this code already exists.");
        }
        permissions.forEach(permission -> rbac.insertRolePermission(org, id, permission));
        audit.record(new AuditEntry(org, actor.userId(), "ROLE_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("code", "name", "permissions")));
        return view(rbac.findRole(org, id).orElseThrow(), List.copyOf(permissions));
    }

    @Transactional
    public RoleView update(AuthenticatedPrincipal actor, UUID roleId, long version, String name,
            List<String> permissionCodes, String currentSessionId) {
        if (name == null && permissionCodes == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide name and/or permissionCodes.");
        }
        UUID org = actor.organizationId();
        rbac.lockOrganization(org);
        RoleRow role = rbac.findRoleForUpdate(org, roleId).orElseThrow(() -> new NotFoundException("Role not found."));
        if (role.version() != version) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
        List<String> changed = new ArrayList<>();
        String newName = role.name();
        if (name != null && !name.trim().equals(role.name())) {
            newName = name.trim();
            changed.add("name");
        }
        boolean permissionsChanged = false;
        if (permissionCodes != null) {
            int adminsBefore = rbac.countActiveAdministrators(org);
            Set<String> wanted = requireKnownPermissions(permissionCodes);
            Set<String> added = new TreeSet<>(wanted);
            added.removeAll(rbac.findPermissionCodesByRoles(org, List.of(roleId)).getOrDefault(roleId, List.of()));
            requireCallerHolds(actor, added);
            permissionsChanged = replacePermissions(org, roleId, wanted);
            if (permissionsChanged) {
                changed.add("permissions");
                if (adminsBefore > 0 && rbac.countActiveAdministrators(org) == 0) {
                    throw new ConflictException("LAST_ADMINISTRATOR",
                            "This would leave the organization without an active system administrator.");
                }
            }
        }
        if (!changed.isEmpty()) {
            rbac.updateRole(org, roleId, newName, clock.instant());
        }
        if (permissionsChanged) {
            for (UUID holder : rbac.findUserIdsHoldingRole(org, roleId)) {
                userAdmin.deleteSessions(holder, actor.userId().equals(holder) ? currentSessionId : null);
            }
        }
        audit.record(new AuditEntry(org, actor.userId(), "ROLE_UPDATED", ENTITY, roleId, AuditOutcome.SUCCESS,
                changed));
        RoleRow updated = rbac.findRole(org, roleId).orElseThrow();
        return view(updated, rbac.findPermissionCodesByRoles(org, List.of(roleId)).getOrDefault(roleId, List.of()));
    }

    private boolean replacePermissions(UUID org, UUID roleId, Set<String> wanted) {
        Set<String> current = new TreeSet<>(
                rbac.findPermissionCodesByRoles(org, List.of(roleId)).getOrDefault(roleId, List.of()));
        boolean changed = false;
        for (String permission : current) {
            if (!wanted.contains(permission)) {
                rbac.deleteRolePermission(org, roleId, permission);
                changed = true;
            }
        }
        for (String permission : wanted) {
            if (!current.contains(permission)) {
                rbac.insertRolePermission(org, roleId, permission);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Anti-escalation rule (api-design.md section 3): a caller may only grant permissions they hold themselves.
     * Permissions the role already has and the caller lacks may stay (or be removed); they cannot be added.
     * There is no exemption: system administrators are bound by it too, so one cannot grant permissions (or assign
     * roles) that carry permissions it lacks.
     */
    static void requireCallerHolds(AuthenticatedPrincipal actor, java.util.Collection<String> permissions) {
        List<String> missing = permissions.stream().filter(p -> !actor.permissions().contains(p)).sorted().toList();
        if (!missing.isEmpty()) {
            throw new ProblemException(HttpStatus.FORBIDDEN, "PRIVILEGE_ESCALATION",
                    "You cannot grant permissions you do not hold yourself.");
        }
    }

    private static Set<String> requireKnownPermissions(List<String> codes) {
        Set<String> wanted = new TreeSet<>(codes);
        List<String> unknown = wanted.stream().filter(code -> !Permissions.ALL.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessRuleException("UNKNOWN_PERMISSION",
                    "Unknown permission code(s): " + String.join(", ", unknown) + ".");
        }
        return wanted;
    }

    private static RoleView view(RoleRow role, List<String> permissions) {
        return new RoleView(role.id(), role.code(), role.name(), permissions, role.version(), role.createdAt(),
                role.updatedAt());
    }
}
