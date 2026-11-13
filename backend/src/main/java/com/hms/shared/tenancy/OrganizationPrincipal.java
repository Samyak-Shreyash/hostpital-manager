package com.hms.shared.tenancy;

import java.util.UUID;

/**
 * An authenticated principal that belongs to one organization. Implemented by identity's principal, so the shared
 * kernel can scope transactions without depending on the identity module (ADR-0010).
 */
public interface OrganizationPrincipal {

    UUID organizationId();

    /** The acting user, for audit attribution. */
    UUID userId();
}
