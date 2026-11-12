package com.hms.identity.api;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The logged-in user as other modules see it (ADR-0005). Stored in the server-side session, so it holds
 * identifiers only: no password hash, no PHI. The organization here is the only source of org scoping
 * (ADR-0010); it is never taken from request input. Role and permission codes are loaded from the database
 * at login (ADR-0006) and stay fixed for the life of the session.
 */
public record AuthenticatedPrincipal(UUID userId, UUID organizationId, String email, String fullName,
        List<String> roles, List<String> permissions) implements Serializable {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(email, "email");
        roles = List.copyOf(roles);
        permissions = List.copyOf(permissions);
    }

    /** Spring Session indexes sessions by this name, so all of a user's sessions can be found and revoked. */
    @Override
    public String toString() {
        return userId.toString();
    }
}
