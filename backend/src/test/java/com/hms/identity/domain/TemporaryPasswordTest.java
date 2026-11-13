package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TemporaryPasswordTest {

    @Test
    void alwaysSatisfiesThePasswordPolicyAndIsUnambiguous() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            String password = TemporaryPassword.generate();
            assertThat(password).hasSize(TemporaryPassword.LENGTH).doesNotContainPattern("[0OIl1]");
            assertThat(PasswordPolicy.check(password, "someone@demo-clinic.example")).isEmpty();
            seen.add(password);
        }
        assertThat(seen).hasSize(500);
    }
}
