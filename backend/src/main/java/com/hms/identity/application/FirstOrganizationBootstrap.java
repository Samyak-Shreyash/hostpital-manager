package com.hms.identity.application;

import com.hms.identity.api.RoleProvisioning;
import com.hms.identity.infrastructure.UserAdminRepository;
import com.hms.organization.api.OrganizationProvisioning;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional part of the first-run bootstrap ([E2-1]): organization, the PRD default roles, and the System
 * Administrator, all or nothing. Identity orchestrates because it owns roles and users while organization only
 * exposes {@link OrganizationProvisioning} (hld.md section 4: identity depends on organization, not the reverse).
 */
@Service
public class FirstOrganizationBootstrap {

    private final OrganizationProvisioning organizations;
    private final RoleProvisioning roles;
    private final UserManagementService users;
    private final UserAdminRepository userAdmin;

    FirstOrganizationBootstrap(OrganizationProvisioning organizations, RoleProvisioning roles,
            UserManagementService users, UserAdminRepository userAdmin) {
        this.organizations = organizations;
        this.roles = roles;
        this.users = users;
        this.userAdmin = userAdmin;
    }

    /** Whether the installation already has an organization (the bootstrap is then a no-op). */
    @Transactional(readOnly = true)
    public boolean organizationExists() {
        return organizations.anyOrganizationExists();
    }

    /**
     * Creates the first organization and its administrator. An advisory lock serializes concurrent instances; the
     * loser finds an organization and returns empty. The temporary password is only hashed here, never logged.
     *
     * @return the new organization's id, or empty when an organization already existed
     */
    @Transactional
    public Optional<UUID> bootstrap(String organizationName, String timezone, String adminEmail,
            String temporaryPassword) {
        userAdmin.lockBootstrap();
        if (organizations.anyOrganizationExists()) {
            return Optional.empty();
        }
        UUID org = organizations.create(organizationName, timezone);
        roles.provisionDefaultRoles(org); // also binds this transaction to the new organization (RLS)
        users.createInitialAdministrator(org, adminEmail, "System Administrator", temporaryPassword);
        return Optional.of(org);
    }
}
