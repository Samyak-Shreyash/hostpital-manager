package com.hms.identity.api;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The logged-in user as other modules see it (ADR-0005). Stored in the server-side session, so it holds
 * identifiers only: no password hash, no PHI. The organization here is the only source of org scoping
 * (ADR-0010); it is never taken from request input.
 */
public record AuthenticatedPrincipal(UUID userId, UUID organizationId, String email, String fullName)
        implements Serializable {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(email, "email");
    }

    /** Spring Session indexes sessions by this name, so all of a user's sessions can be found and revoked. */
    @Override
    public String toString() {
        return userId.toString();
    }
}
