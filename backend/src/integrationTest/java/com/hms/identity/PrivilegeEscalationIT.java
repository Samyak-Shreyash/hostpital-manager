package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Final-review fixes: no privilege escalation through role grants or role assignment, and a login does not bump
 * {@code app_user.version} (api-design.md section 3). Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PrivilegeEscalationIT extends PostgresIT {

    private static final String ROLES = "/api/v1/roles";
    private static final String USERS = "/api/v1/users";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RoleProvisioning roleProvisioning;

    @Autowired
    OrganizationContext organizationContext;

    @Autowired
    PlatformTransactionManager transactionManager;

    private AdminApiFixture fx;
    private UUID org;

    @BeforeEach
    void organization() {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
    }

    @Test
    void aRoleManagerCannotGrantAPermissionItLacks() throws Exception {
        fx.role(org, "FACILITY_LIKE", "role:manage", "role:read", "patient:read");
        AdminApiFixture.Session manager = fx.loggedIn(org, "facility", "FACILITY_LIKE");

        mockMvc.perform(manager.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SNEAKY\",\"name\":\"Sneaky\",\"permissionCodes\":[\"user:update\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        assertThat(jdbc.sql("SELECT count(*) FROM hms.role WHERE organization_id = ? AND code = 'SNEAKY'")
                .param(org).query(Integer.class).single()).isZero();

        UUID nurse = fx.roleId(org, "NURSE");
        long version = fx.inOrg(org, () -> jdbc.sql("SELECT version FROM hms.role WHERE id = ?").param(nurse)
                .query(Long.class).single());
        mockMvc.perform(manager.apply(patch(ROLES + "/" + nurse).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"permissionCodes\":[\"user:update\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));

        // Granting what it holds is fine.
        mockMvc.perform(manager.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"READER\",\"name\":\"Reader\",\"permissionCodes\":[\"patient:read\"]}")))
                .andExpect(status().isCreated());
    }

    @Test
    void aUserUpdaterCannotAssignSystemAdminButACallerHoldingASupersetCan() throws Exception {
        fx.role(org, "HR_CLERK", "user:update", "user:read", "user:create");
        AdminApiFixture.Session clerk = fx.loggedIn(org, "clerk", "HR_CLERK");
        AdminApiFixture.Session superset = fx.withAllPermissions(org);
        UUID target = fx.user(org, fx.uniqueEmail("target"), "RECEPTIONIST");

        String body = "{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"SYSTEM_ADMIN\"]}";
        mockMvc.perform(clerk.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mockMvc.perform(clerk.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + fx.uniqueEmail("new") + "\",\"fullName\":\"Synthetic New\","
                                + "\"roleCodes\":[\"SYSTEM_ADMIN\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT r.code FROM hms.user_role ur JOIN hms.role r "
                + "ON r.id = ur.role_id WHERE ur.user_id = ?").param(target).query(String.class).list()))
                .isEqualTo(List.of("RECEPTIONIST"));

        mockMvc.perform(superset.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk());
    }

    @Test
    void holdingUserUpdateAndRoleManageIsNotAnExemption() throws Exception {
        fx.role(org, "ROLE_AND_USER_ADMIN", "user:update", "user:create", "user:read", "role:manage", "role:read");
        AdminApiFixture.Session caller = fx.loggedIn(org, "both", "ROLE_AND_USER_ADMIN");
        UUID target = fx.user(org, fx.uniqueEmail("target"), "NURSE");

        mockMvc.perform(caller.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"DOCTOR\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mockMvc.perform(caller.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NOPE\",\"name\":\"Nope\",\"permissionCodes\":[\"patient:read\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
    }

    @Test
    void aSystemAdministratorCanAssignEveryBuiltInRole() throws Exception {
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        UUID target = fx.user(org, fx.uniqueEmail("target"), "NURSE");

        for (String code : List.of("DOCTOR", "NURSE", "RECEPTIONIST", "ACCOUNTANT", "FACILITY_ADMIN")) {
            mockMvc.perform(admin.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"" + code + "\"]}")))
                    .andExpect(status().isOk());
            mockMvc.perform(admin.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + fx.uniqueEmail("new") + "\",\"fullName\":\"Synthetic New\","
                                    + "\"roleCodes\":[\"" + code + "\"]}")))
                    .andExpect(status().isCreated());
        }
    }

    @Test
    void roleAssignDoesNotCoverACustomRoleCarryingPermissionsTheCallerLacks() throws Exception {
        fx.role(org, "CLINICAL_CUSTOM", "patient:read", "encounter:read");
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        UUID target = fx.user(org, fx.uniqueEmail("target"), "NURSE");

        mockMvc.perform(admin.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"CLINICAL_CUSTOM\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mockMvc.perform(admin.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + fx.uniqueEmail("new") + "\",\"fullName\":\"Synthetic New\","
                                + "\"roleCodes\":[\"CLINICAL_CUSTOM\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
    }

    @Test
    void roleAssignDoesNotLetAnAdministratorGrantPermissionsToARole() throws Exception {
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        UUID nurse = fx.roleId(org, "NURSE");
        long version = fx.inOrg(org, () -> jdbc.sql("SELECT version FROM hms.role WHERE id = ?").param(nurse)
                .query(Long.class).single());

        mockMvc.perform(admin.apply(patch(ROLES + "/" + nurse).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"permissionCodes\":[\"bed:assign\","
                                + "\"invoice:issue\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
        mockMvc.perform(admin.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"SNEAKY\",\"name\":\"Sneaky\",\"permissionCodes\":[\"patient:read\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
    }

    /** Fallback rule: a built-in role edited beyond its template is assignable only under the strict rule. */
    @Test
    void aBuiltInRoleEditedBeyondItsTemplateFallsBackToTheStrictRule() throws Exception {
        UUID nurse = fx.roleId(org, "NURSE");
        fx.inOrg(org, () -> jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) "
                + "VALUES (?, ?, 'invoice:issue')").params(org, nurse).update());
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        UUID target = fx.user(org, fx.uniqueEmail("target"), "DOCTOR");

        mockMvc.perform(admin.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"NURSE\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));

        // A caller holding everything satisfies the strict rule.
        AdminApiFixture.Session superset = fx.withAllPermissions(org);
        mockMvc.perform(superset.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(target) + ",\"roleCodes\":[\"NURSE\"]}")))
                .andExpect(status().isOk());

        // Narrowing a built-in role below its template keeps role:assign applicable; back within the template again.
        fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.role_permission WHERE role_id = ? AND permission_code = ?")
                .params(nurse, "invoice:issue").update());
        fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.role_permission WHERE role_id = ? AND permission_code = ?")
                .params(nurse, "bed:assign").update());
        UUID other = fx.user(org, fx.uniqueEmail("other"), "DOCTOR");
        mockMvc.perform(admin.apply(patch(USERS + "/" + other).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(other) + ",\"roleCodes\":[\"NURSE\"]}")))
                .andExpect(status().isOk());
    }

    @Test
    void withoutRoleAssignTheStrictRuleStillAppliesToBuiltInRoles() throws Exception {
        fx.role(org, "USER_ONLY", "user:create", "user:update", "user:read");
        AdminApiFixture.Session caller = fx.loggedIn(org, "useronly", "USER_ONLY");

        mockMvc.perform(caller.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + fx.uniqueEmail("new") + "\",\"fullName\":\"Synthetic New\","
                                + "\"roleCodes\":[\"NURSE\"]}")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVILEGE_ESCALATION"));
    }

    /** V12 backfill: re-runs its statements (idempotent) against a SYSTEM_ADMIN role created before the change. */
    @Test
    void migrationBackfillsRoleAssignOntoExistingSystemAdministratorRolesIdempotently() throws Exception {
        UUID adminRole = fx.roleId(org, "SYSTEM_ADMIN");
        fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.role_permission WHERE role_id = ? AND permission_code = 'role:assign'")
                .param(adminRole).update());
        assertThat(roleAssignRows(adminRole)).isZero();

        String migration = new String(getClass().getResourceAsStream("/db/migration/V12__role_assign_permission.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        // The catalogue insert needs the owner role; the app role used here runs the backfill block only.
        String backfill = migration.substring(migration.indexOf("DO $$"));
        for (int run = 0; run < 2; run++) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> jdbc.sql(backfill).update());
        }
        assertThat(roleAssignRows(adminRole)).isEqualTo(1);
        assertThat(roleAssignRows(fx.roleId(org, "NURSE"))).isZero();
    }

    private int roleAssignRows(UUID roleId) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.role_permission WHERE role_id = ? "
                + "AND permission_code = 'role:assign'").param(roleId).query(Integer.class).single());
    }

    @Test
    void loginsDoNotBumpTheUserVersion() throws Exception {
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        String email = fx.uniqueEmail("staff");
        UUID staff = fx.user(org, email, "RECEPTIONIST");
        long version = fx.version(staff);

        Cookie csrf = fx.csrf();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"Wrong-Synthetic-999\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        fx.login(staff, email);
        assertThat(fx.version(staff)).isEqualTo(version);

        mockMvc.perform(admin.apply(patch(USERS + "/" + staff).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"fullName\":\"Synthetic Renamed\"}")))
                .andExpect(status().isOk());
    }
}
