package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LoginLockoutTest {

    private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void ruleIsFiveFailuresForFifteenMinutes() {
        assertThat(LoginLockout.MAX_FAILURES).isEqualTo(5);
        assertThat(LoginLockout.LOCK_DURATION).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void lockHoldsUntilButNotAtItsEnd() {
        assertThat(LoginLockout.isLocked(null, NOW)).isFalse();
        assertThat(LoginLockout.isLocked(NOW.plusSeconds(1), NOW)).isTrue();
        assertThat(LoginLockout.isLocked(NOW, NOW)).isFalse();
    }

    @Test
    void failuresAccumulate() {
        assertThat(LoginLockout.failuresAfterFailedAttempt(0, null, NOW)).isEqualTo(1);
        assertThat(LoginLockout.failuresAfterFailedAttempt(3, null, NOW)).isEqualTo(4);
    }

    @Test
    void expiredLockStartsAFreshCount() {
        assertThat(LoginLockout.failuresAfterFailedAttempt(5, NOW.minusSeconds(1), NOW)).isEqualTo(1);
    }

    @Test
    void locksFromTheFifthFailure() {
        assertThat(LoginLockout.lockUntil(4, NOW)).isNull();
        assertThat(LoginLockout.lockUntil(5, NOW)).isEqualTo(NOW.plus(LoginLockout.LOCK_DURATION));
        assertThat(LoginLockout.lockUntil(6, NOW)).isEqualTo(NOW.plus(LoginLockout.LOCK_DURATION));
    }
}
