package com.hms.identity.application;

/** Outcome of an own-password change. Failures are values so their lockout counter and audit row commit. */
public sealed interface ChangePasswordResult {

    record Changed() implements ChangePasswordResult {
    }

    record WrongCurrentPassword() implements ChangePasswordResult {
    }

    record Locked() implements ChangePasswordResult {
    }
}
