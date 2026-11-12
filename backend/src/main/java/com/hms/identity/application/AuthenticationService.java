package com.hms.identity.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.domain.LoginLockout;
import com.hms.identity.domain.PasswordPolicy;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.identity.infrastructure.AppUserEntity;
import com.hms.identity.infrastructure.AppUserRepository;
import com.hms.identity.infrastructure.RbacRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
    private final OrganizationContext organizationContext;
    private final Clock clock;
    /** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
    private final String timingEqualiserHash;

    public AuthenticationService(
            AppUserRepository users, PasswordEncoder passwordEncoder, AuditRecorder audit, RbacRepository rbac,
            OrganizationContext organizationContext, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.rbac = rbac;
        this.organizationContext = organizationContext;
        this.clock = clock;
        this.timingEqualiserHash = passwordEncoder.encode("timing-equaliser-not-a-password");
    }

    @Transactional
    public LoginResult login(String email, String password) {
        Instant now = clock.instant();
        Optional<AppUserEntity> found = users.findByEmailForUpdate(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            // No organization to attribute an audit event to, and audit_event.organization_id is mandatory.
            matches(password, timingEqualiserHash);
            return new LoginResult.InvalidCredentials();
        }
        AppUserEntity user = found.get();
        // app_user is outside RLS (the org is unknown until this lookup); the role tables are not, so the rest
        // of this transaction runs in the user's organization (ADR-0010, database-design.md section 14).
        organizationContext.bindToCurrentTransaction(user.getOrganizationId());

        if (LoginLockout.isLocked(user.getLockedUntil(), now)) {
            record(user, null, "LOGIN_FAILED", AuditOutcome.DENIED);
            return new LoginResult.Locked();
        }
        if (!matches(password, user.getPasswordHash())) {
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

    /** Read per request by the password-change gate, so a completed change takes effect immediately. */
    @Transactional(readOnly = true)
    public boolean isPasswordChangeRequired(UUID userId) {
        // Fails closed: a missing row (user gone while the session lives) is treated as change required.
        return users.findById(userId).map(AppUserEntity::isMustChangePassword).orElse(true);
    }

    /**
     * Own password change (IAM-4). The new password must satisfy {@link PasswordPolicy} and differ from the
     * current one. A wrong current password counts towards the login lockout, so a hijacked session cannot
     * be used to guess the password. Audit carries the field names only.
     */
    @Transactional
    public ChangePasswordResult changePassword(UUID userId, String currentPassword, String newPassword) {
        Instant now = clock.instant();
        AppUserEntity user = users.findByIdForUpdate(userId).orElseThrow();
        if (LoginLockout.isLocked(user.getLockedUntil(), now)) {
            record(user, userId, "PASSWORD_CHANGE_FAILED", AuditOutcome.DENIED);
            return new ChangePasswordResult.Locked();
        }
        if (!matches(currentPassword, user.getPasswordHash())) {
            int failures = LoginLockout.failuresAfterFailedAttempt(
                    user.getFailedLoginCount(), user.getLockedUntil(), now);
            user.recordLoginOutcome(failures, LoginLockout.lockUntil(failures, now), now);
            record(user, userId, "PASSWORD_CHANGE_FAILED", AuditOutcome.FAILURE);
            return new ChangePasswordResult.WrongCurrentPassword();
        }
        PasswordPolicy.require(newPassword, user.getEmail()); // throws 422; nothing has changed yet
        if (newPassword.equals(currentPassword)) {
            throw new BusinessRuleException(PasswordPolicy.VIOLATION_CODE,
                    "Password must be different from the current password.");
        }
        user.changePassword(passwordEncoder.encode(newPassword), now);
        audit.record(new AuditEntry(user.getOrganizationId(), userId, "PASSWORD_CHANGED", ENTITY, userId,
                AuditOutcome.SUCCESS, List.of("passwordHash", "mustChangePassword")));
        return new ChangePasswordResult.Changed();
    }

    /**
     * BCrypt rejects passwords over 72 bytes with an exception. Such a password can never be correct (the policy
     * forbids setting one), so it is a plain mismatch, still costing one BCrypt check so timing does not differ.
     */
    private boolean matches(String raw, String hash) {
        if (PasswordPolicy.exceedsBcryptLimit(raw)) {
            passwordEncoder.matches("x", hash);
            return false;
        }
        return passwordEncoder.matches(raw, hash);
    }

    private void record(AppUserEntity user, UUID actorId, String action, AuditOutcome outcome) {
        audit.record(AuditEntry.of(user.getOrganizationId(), actorId, action, ENTITY, user.getId(), outcome));
    }
}
