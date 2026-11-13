package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.organization.api.OrganizationSummary;
import java.util.List;
import java.util.UUID;

/**
 * api-design.md section 2 login response. Roles and permissions are those loaded into the session at login
 * (ADR-0006); the organization carries its id, name and timezone ([E2-1]).
 */
record LoginResponse(User user, List<String> permissions, Organization organization) {

    record User(UUID id, String fullName, List<String> roles, boolean mustChangePassword) {
    }

    record Organization(UUID id, String name, String timezone) {
    }

    static LoginResponse of(AuthenticatedPrincipal principal, boolean mustChangePassword,
            OrganizationSummary organization) {
        return new LoginResponse(
                new User(principal.userId(), principal.fullName(), principal.roles(), mustChangePassword),
                principal.permissions(),
                new Organization(organization.id(), organization.name(), organization.timezone()));
    }
}
