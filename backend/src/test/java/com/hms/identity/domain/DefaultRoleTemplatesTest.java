package com.hms.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.hms.identity.api.Permissions;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DefaultRoleTemplatesTest {

    private final Map<String, Set<String>> roles = DefaultRoleTemplates.ALL.stream()
            .collect(Collectors.toMap(DefaultRoleTemplates.Template::code, DefaultRoleTemplates.Template::permissions));

    @Test
    void definesThePrdPersonas() {
        assertThat(roles).containsOnlyKeys(
                "SYSTEM_ADMIN", "FACILITY_ADMIN", "DOCTOR", "NURSE", "RECEPTIONIST", "ACCOUNTANT");
    }

    @Test
    void wildcardExpandsToEveryCodeWithThePrefix() {
        assertThat(DefaultRoleTemplates.expand("user:*"))
                .containsExactlyInAnyOrder("user:create", "user:read", "user:update", "user:reset-password");
    }

    @Test
    void nurseGetsExactlyThePrdPermissions() {
        assertThat(roles.get("NURSE")).containsExactlyInAnyOrder(
                Permissions.PATIENT_READ, Permissions.OBSERVATION_WRITE, Permissions.BED_ASSIGN,
                Permissions.ADMISSION_READ);
    }

    @Test
    void systemAdministratorHasNoClinicalAccess() {
        assertThat(roles.get("SYSTEM_ADMIN"))
                .noneMatch(code -> code.startsWith("patient:") || code.startsWith("encounter:"))
                .contains(Permissions.AUDIT_READ, Permissions.ROLE_MANAGE, Permissions.ORG_UPDATE);
    }

    @Test
    void unknownCodesAndEmptyWildcardsAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> DefaultRoleTemplates.expand("patient:teleport"));
        assertThatIllegalArgumentException().isThrownBy(() -> DefaultRoleTemplates.expand("staff:*"));
    }

    @Test
    void everyTemplatePermissionIsInTheCatalogue() {
        roles.values().forEach(codes -> assertThat(Permissions.ALL).containsAll(codes));
    }
}
