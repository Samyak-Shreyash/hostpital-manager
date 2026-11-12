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
    void aUserUpdaterCannotAssignSystemAdminButASystemAdminCan() throws Exception {
        fx.role(org, "HR_CLERK", "user:update", "user:read", "user:create");
        AdminApiFixture.Session clerk = fx.loggedIn(org, "clerk", "HR_CLERK");
        AdminApiFixture.Session admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
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

        mockMvc.perform(admin.apply(patch(USERS + "/" + target).contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isOk());
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
