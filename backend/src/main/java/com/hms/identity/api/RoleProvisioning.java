package com.hms.identity.api;

import java.util.UUID;

/**
 * Creates the PRD section 3 default roles in an organization (ADR-0006: roles are org-owned data). Meant to be
 * called by organization provisioning; afterwards the roles are ordinary, editable data.
 */
public interface RoleProvisioning {

    /** Idempotent: a role whose code already exists in the organization is left unchanged. */
    void provisionDefaultRoles(UUID organizationId);
}
