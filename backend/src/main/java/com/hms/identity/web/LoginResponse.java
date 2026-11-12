package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;

/**
 * api-design.md section 2 login response. Roles and permissions are empty until RBAC ([E1-5]); the
 * organization carries only its id until organization settings exist ([E2-1]).
 */
record LoginResponse(User user, List<String> permissions, Organization organization) {

    record User(UUID id, String fullName, List<String> roles, boolean mustChangePassword) {
    }

    record Organization(UUID id) {
    }

    static LoginResponse of(AuthenticatedPrincipal principal, boolean mustChangePassword) {
        return new LoginResponse(
                new User(principal.userId(), principal.fullName(), List.of(), mustChangePassword),
                List.of(),
                new Organization(principal.organizationId()));
    }
}
