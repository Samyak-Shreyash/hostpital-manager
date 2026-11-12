package com.hms.identity.application;

/**
 * Result of creating a user or resetting a password: the only place the temporary password exists in clear text.
 * It is returned to the administrator once and never stored, logged or audited, so {@code toString} hides it.
 */
public record UserWithTemporaryPassword(UserView user, String temporaryPassword) {

    @Override
    public String toString() {
        return "UserWithTemporaryPassword[user=" + user.id() + ", temporaryPassword=***]";
    }
}
