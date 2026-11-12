package com.hms.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Account lockout rule (PRD IAM-5, api-design.md section 2): {@value #MAX_FAILURES} consecutive failed
 * logins lock the account for {@link #LOCK_DURATION}. A successful login resets the count.
 */
public final class LoginLockout {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private LoginLockout() {
    }

    public static boolean isLocked(Instant lockedUntil, Instant now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    /**
     * Failure count after one more failed attempt. A lock that has already expired starts a fresh count,
     * so an old streak does not re-lock the account on the next single mistake.
     */
    public static int failuresAfterFailedAttempt(int previousFailures, Instant lockedUntil, Instant now) {
        boolean expiredLock = lockedUntil != null && !now.isBefore(lockedUntil);
        return (expiredLock ? 0 : previousFailures) + 1;
    }

    /** When the account should be locked until after reaching {@code failures}, or {@code null} for no lock. */
    public static Instant lockUntil(int failures, Instant now) {
        return failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
    }
}
