package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.identity.domain.PasswordPolicy.Violation;
import com.hms.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private static final String EMAIL = "demo.user@demo-clinic.example";

    @Test
    void acceptsLengthBoundaries() {
        assertThat(PasswordPolicy.check("a".repeat(12), EMAIL)).isEmpty();
        assertThat(PasswordPolicy.check("a".repeat(72), EMAIL)).isEmpty();
    }

    @Test
    void rejectsTooShort() {
        assertThat(PasswordPolicy.check("a".repeat(11), EMAIL)).containsExactly(Violation.TOO_SHORT);
    }

    @Test
    void rejectsTooLong() {
        assertThat(PasswordPolicy.check("a".repeat(73), EMAIL)).containsExactly(Violation.TOO_LONG);
        // 36 two-byte characters are exactly 72 bytes; 37 are 74
        assertThat(PasswordPolicy.check("\u00e9".repeat(36), EMAIL)).isEmpty();
        assertThat(PasswordPolicy.check("\u00e9".repeat(37), EMAIL)).containsExactly(Violation.TOO_LONG);
    }

    @Test
    void rejectsBlankAndNull() {
        assertThat(PasswordPolicy.check(" ".repeat(20), EMAIL)).containsExactly(Violation.BLANK);
        assertThat(PasswordPolicy.check(null, EMAIL)).containsExactly(Violation.BLANK);
    }

    @Test
    void rejectsEmailIgnoringCaseAndPadding() {
        assertThat(PasswordPolicy.check(EMAIL.toUpperCase(), EMAIL)).containsExactly(Violation.SAME_AS_EMAIL);
        assertThat(PasswordPolicy.check(" " + EMAIL, EMAIL)).containsExactly(Violation.SAME_AS_EMAIL);
    }

    @Test
    void requireThrowsRuleNamesWithoutThePassword() {
        String password = "short-pw";
        assertThatThrownBy(() -> PasswordPolicy.require(password, EMAIL))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("at least 12")
                .hasMessageNotContaining(password)
                .extracting(e -> ((BusinessRuleException) e).code()).isEqualTo("PASSWORD_POLICY_VIOLATION");
        assertThatCode(() -> PasswordPolicy.require("a-long-enough-phrase", EMAIL)).doesNotThrowAnyException();
    }
}
