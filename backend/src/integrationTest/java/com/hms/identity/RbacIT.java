package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.identity.domain.DefaultRoleTemplates;
import com.hms.support.PostgresIT;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * [E1-5] RBAC (ADR-0006): catalogue seeding, org-owned roles, authorities loaded at login. Synthetic data only.
 * Every test uses its own organization id, so roles and users never leak between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(RbacIT.ProbeController.class)
class RbacIT extends PostgresIT {

    private static final String PASSWORD = "Synthetic-Pass-123";
    private static final String SESSION_COOKIE = "HMSSESSION";
    private static final String PROBE = "/api/v1/test-rbac-probe";

    /** Test-only endpoint guarded by a permission, to observe the authorities of a session. */
    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        @PreAuthorize("hasAuthority('patient:read')")
        String probe() {
            return "ok";
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    RoleProvisioning roleProvisioning;

    private static String passwordHash;
    private UUID org;
    private UUID userId;
    private String email;

    @BeforeEach
    void createUser() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        org = UUID.randomUUID();
        userId = UUID.randomUUID();
        email = "rbac-" + userId + "@demo-clinic.example";
        insertUser(org, userId, email);
    }

    @Test
    void permissionTableMatchesTheCodeDefinedCatalogue() {
        List<String> seeded = jdbc.sql("SELECT code FROM hms.permission").query(String.class).list();
        assertThat(seeded).containsExactlyInAnyOrderElementsOf(Permissions.ALL);
    }

    @Test
    void appRoleCannotChangeThePermissionCatalogue() {
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO hms.permission (code) VALUES ('test:extra')").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied for table permission");
    }

    @Test
    void loginLoadsRolesAndPermissionsIntoSessionAndResponse() throws Exception {
        UUID receptionist = insertRole(org, "FRONT_DESK", Permissions.PATIENT_READ, Permissions.PATIENT_CREATE);
        UUID billing = insertRole(org, "CASHIER", Permissions.PAYMENT_CREATE, Permissions.PATIENT_READ);
        assignRole(org, userId, receptionist);
        assignRole(org, userId, billing);

        Cookie session = loginSession()
                .andExpect(jsonPath("$.user.roles").value(contains("CASHIER", "FRONT_DESK")))
                .andExpect(jsonPath("$.permissions").value(contains(
                        "patient:create", "patient:read", "payment:create")))
                .andReturn().getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().map(RbacIT::toCookie).orElseThrow();

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.roles").value(contains("CASHIER", "FRONT_DESK")))
                .andExpect(jsonPath("$.permissions.length()").value(3));
        mockMvc.perform(get(PROBE).cookie(session)).andExpect(status().isOk());
    }

    @Test
    void userWithoutThePermissionIsForbiddenAndAnonymousIsUnauthorized() throws Exception {
        assignRole(org, userId, insertRole(org, "NO_PATIENTS", Permissions.PAYMENT_READ));
        Cookie session = login();

        // The same session reaches an authenticated endpoint, so the 403 below is about the permission only.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session)).andExpect(status().isOk());
        mockMvc.perform(get(PROBE).cookie(session)).andExpect(status().isForbidden());
        mockMvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
    }

    @Test
    void rolePermissionChangeTakesEffectOnTheNextLogin() throws Exception {
        UUID role = insertRole(org, "CLERK", Permissions.PAYMENT_READ);
        assignRole(org, userId, role);
        Cookie before = login();
        mockMvc.perform(get(PROBE).cookie(before)).andExpect(status().isForbidden());

        grant(org, role, Permissions.PATIENT_READ);

        // The existing session keeps the authorities it was created with ...
        mockMvc.perform(get(PROBE).cookie(before)).andExpect(status().isForbidden());
        // ... and the next login picks up the change.
        Cookie after = login();
        mockMvc.perform(get(PROBE).cookie(after)).andExpect(status().isOk());
    }

    @Test
    void roleOfAnotherOrganizationCannotBeAssignedOrGranted() {
        UUID otherOrg = UUID.randomUUID();
        UUID foreignRole = insertRole(otherOrg, "FOREIGN", Permissions.PATIENT_READ);

        assertThatThrownBy(() -> assignRole(org, userId, foreignRole))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_user_role_role");
        assertThatThrownBy(() -> grant(org, foreignRole, Permissions.PATIENT_CREATE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_role_permission_role");
        assertThatThrownBy(() -> assignRole(otherOrg, userId, foreignRole))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_user_role_user");
    }

    @Test
    void unknownPermissionCannotBeGranted() {
        UUID role = insertRole(org, "EMPTY");
        assertThatThrownBy(() -> grant(org, role, "patient:teleport"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_role_permission_permission");
    }

    @Test
    void defaultRolesAreProvisionedFromThePrdTemplatesIdempotently() {
        roleProvisioning.provisionDefaultRoles(org);
        roleProvisioning.provisionDefaultRoles(org);

        Map<String, Set<String>> stored = jdbc.sql("""
                SELECT r.code, rp.permission_code FROM hms.role r
                JOIN hms.role_permission rp ON rp.organization_id = r.organization_id AND rp.role_id = r.id
                WHERE r.organization_id = ?
                """)
                .param(org)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().stream()
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));
        Map<String, Set<String>> expected = DefaultRoleTemplates.ALL.stream()
                .collect(Collectors.toMap(DefaultRoleTemplates.Template::code,
                        DefaultRoleTemplates.Template::permissions));
        assertThat(stored).isEqualTo(expected);
    }

    private Cookie login() throws Exception {
        MvcResult result = loginSession().andReturn();
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().map(RbacIT::toCookie).orElseThrow();
    }

    private ResultActions loginSession() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk());
    }

    private static Cookie toCookie(String setCookieHeader) {
        String pair = setCookieHeader.split(";", 2)[0];
        return new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1));
    }

    private void insertUser(UUID organizationId, UUID id, String userEmail) {
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo User', ?, 'ACTIVE', false, now(), now())
                """)
                .params(id, organizationId, userEmail, passwordHash)
                .update();
    }

    private UUID insertRole(UUID organizationId, String code, String... permissions) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """)
                .params(id, organizationId, code, "Demo " + code)
                .update();
        for (String permission : permissions) {
            grant(organizationId, id, permission);
        }
        return id;
    }

    private void grant(UUID organizationId, UUID roleId, String permission) {
        jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) VALUES (?, ?, ?)")
                .params(organizationId, roleId, permission)
                .update();
    }

    private void assignRole(UUID organizationId, UUID user, UUID roleId) {
        jdbc.sql("INSERT INTO hms.user_role (organization_id, user_id, role_id) VALUES (?, ?, ?)")
                .params(organizationId, user, roleId)
                .update();
    }
}
