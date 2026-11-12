package com.hms.identity.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.domain.LoginLockout;
import com.hms.identity.infrastructure.AppUserEntity;
import com.hms.identity.infrastructure.AppUserRepository;
import com.hms.identity.infrastructure.RbacRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password login with lockout and audit (ADR-0005, PRD IAM-1/5/6, hld.md section 7). Session creation and
 * cookies are the web layer's job; this service decides the outcome and records it in one transaction.
 */
@Service
public class AuthenticationService {

    static final String ENTITY = "APP_USER";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditRecorder audit;
    private final RbacRepository rbac;
    private final Clock clock;
    /** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
    private final String timingEqualiserHash;

    public AuthenticationService(
            AppUserRepository users, PasswordEncoder passwordEncoder, AuditRecorder audit, RbacRepository rbac,
            Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.rbac = rbac;
        this.clock = clock;
        this.timingEqualiserHash = passwordEncoder.encode("timing-equaliser-not-a-password");
    }

    @Transactional
    public LoginResult login(String email, String password) {
        Instant now = clock.instant();
        Optional<AppUserEntity> found = users.findByEmailForUpdate(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            // No organization to attribute an audit event to, and audit_event.organization_id is mandatory.
            passwordEncoder.matches(password, timingEqualiserHash);
            return new LoginResult.InvalidCredentials();
        }
        AppUserEntity user = found.get();

        if (LoginLockout.isLocked(user.getLockedUntil(), now)) {
            record(user, null, "LOGIN_FAILED", AuditOutcome.DENIED);
            return new LoginResult.Locked();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            int failures = LoginLockout.failuresAfterFailedAttempt(
                    user.getFailedLoginCount(), user.getLockedUntil(), now);
            Instant lockedUntil = LoginLockout.lockUntil(failures, now);
            user.recordLoginOutcome(failures, lockedUntil, now);
            record(user, null, "LOGIN_FAILED", AuditOutcome.FAILURE);
            if (lockedUntil != null) {
                record(user, null, "ACCOUNT_LOCKED", AuditOutcome.SUCCESS);
                return new LoginResult.Locked();
            }
            return new LoginResult.InvalidCredentials();
        }
        if (!user.isActive()) {
            record(user, null, "LOGIN_FAILED", AuditOutcome.DENIED);
            return new LoginResult.Inactive();
        }

        user.recordLoginOutcome(0, null, now);
        record(user, user.getId(), "LOGIN_SUCCEEDED", AuditOutcome.SUCCESS);
        // Effective permissions are loaded once per login (ADR-0006): role changes apply from the next login.
        return new LoginResult.Succeeded(
                new AuthenticatedPrincipal(user.getId(), user.getOrganizationId(), user.getEmail(), user.getFullName(),
                        rbac.findRoleCodes(user.getOrganizationId(), user.getId()),
                        rbac.findPermissionCodes(user.getOrganizationId(), user.getId())),
                user.isMustChangePassword());
    }

    @Transactional
    public void recordLogout(AuthenticatedPrincipal principal) {
        audit.record(AuditEntry.of(principal.organizationId(), principal.userId(), "LOGOUT", ENTITY,
                principal.userId(), AuditOutcome.SUCCESS));
    }

    private void record(AppUserEntity user, UUID actorId, String action, AuditOutcome outcome) {
        audit.record(AuditEntry.of(user.getOrganizationId(), actorId, action, ENTITY, user.getId(), outcome));
    }
}
