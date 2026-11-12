package com.hms.identity.application;

import com.hms.identity.api.AuthenticatedPrincipal;

/** Outcome of a login attempt. Failures are values, not exceptions, so their audit and lockout state commit. */
public sealed interface LoginResult {

    record Succeeded(AuthenticatedPrincipal principal, boolean mustChangePassword) implements LoginResult {
    }

    /** Unknown email or wrong password; deliberately indistinguishable to the caller. */
    record InvalidCredentials() implements LoginResult {
    }

    record Locked() implements LoginResult {
    }

    record Inactive() implements LoginResult {
    }
}
