package com.hms.identity.application;

import com.hms.identity.api.RoleProvisioning;
import com.hms.identity.domain.DefaultRoleTemplates;
import com.hms.identity.infrastructure.RbacRepository;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationContext;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Copies {@link DefaultRoleTemplates} into an organization (ADR-0006). */
@Service
class RoleProvisioningService implements RoleProvisioning {

    private final RbacRepository rbac;
    private final UuidV7Generator ids;
    private final OrganizationContext organizationContext;
    private final Clock clock;

    RoleProvisioningService(RbacRepository rbac, UuidV7Generator ids, OrganizationContext organizationContext,
            Clock clock) {
        this.rbac = rbac;
        this.ids = ids;
        this.organizationContext = organizationContext;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void provisionDefaultRoles(UUID organizationId) {
        // Called while creating an organization, before anyone can log in to it; refused for a foreign org.
        organizationContext.bindToCurrentTransaction(organizationId);
        Instant now = clock.instant();
        for (DefaultRoleTemplates.Template template : DefaultRoleTemplates.ALL) {
            UUID roleId = ids.next();
            if (rbac.insertRoleIfAbsent(roleId, organizationId, template.code(), template.name(), now)) {
                template.permissions().stream().sorted()
                        .forEach(code -> rbac.insertRolePermission(organizationId, roleId, code));
            }
        }
    }
}
