package com.hms.identity.domain;

import com.hms.shared.domain.BusinessRuleException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Password policy applied whenever a password is set: own change ([E1-4]) and, later, admin-set temporary
 * passwords (user management). The PRD gives no numbers, so the defaults are: 12 characters to 72 UTF-8 bytes (BCrypt ignores or rejects anything longer), not blank,
 * not equal to the account email, no composition rules and no history (a change must still differ from the
 * current password, checked by the caller that knows the current one). Login does not apply the policy.
 * Violations name the rule only, never the password.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_BYTES = 72;
    public static final String VIOLATION_CODE = "PASSWORD_POLICY_VIOLATION";

    public enum Violation {
        TOO_SHORT("must be at least " + MIN_LENGTH + " characters"),
        TOO_LONG("must be at most " + MAX_BYTES + " bytes (UTF-8); multi-byte characters count for more than one"),
        BLANK("must not be blank"),
        SAME_AS_EMAIL("must not be the same as the email address");

        private final String message;

        Violation(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private PasswordPolicy() {
    }

    /** True when BCrypt cannot take the password: more than {@link #MAX_BYTES} UTF-8 bytes. */
    public static boolean exceedsBcryptLimit(String password) {
        return password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES;
    }

    /** All rules the password breaks; empty when acceptable. */
    public static List<Violation> check(String password, String email) {
        List<Violation> violations = new ArrayList<>();
        if (password == null || password.isBlank()) {
            violations.add(Violation.BLANK);
            return violations;
        }
        if (password.length() < MIN_LENGTH) {
            violations.add(Violation.TOO_SHORT);
        }
        if (exceedsBcryptLimit(password)) {
            violations.add(Violation.TOO_LONG);
        }
        if (email != null && password.trim().toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT))) {
            violations.add(Violation.SAME_AS_EMAIL);
        }
        return violations;
    }

    /** @throws BusinessRuleException 422 {@code PASSWORD_POLICY_VIOLATION} naming the broken rules */
    public static void require(String password, String email) {
        List<Violation> violations = check(password, email);
        if (!violations.isEmpty()) {
            throw new BusinessRuleException(VIOLATION_CODE, "Password " + String.join("; ",
                    violations.stream().map(Violation::message).toList()) + ".");
        }
    }
}
