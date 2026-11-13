package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.TestOrganizations;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test fixture for the user and role management ITs: synthetic organizations with the default roles, users with
 * given roles, and logged-in sessions. Every test builds its own organization, so nothing leaks between tests.
 */
public final class AdminApiFixture {

    static final String PASSWORD = "Synthetic-Pass-123";
    private static final String SESSION_COOKIE = "HMSSESSION";

    /** A logged-in session plus the CSRF token, applied to any request. */
    public record Session(UUID userId, String email, Cookie session, Cookie csrf) {
        public MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private final MockMvc mockMvc;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwordEncoder;
    private final RoleProvisioning roleProvisioning;
    private final OrganizationContext organizationContext;
    private final PlatformTransactionManager transactionManager;
    private final String passwordHash;

    public AdminApiFixture(MockMvc mockMvc, JdbcClient jdbc, PasswordEncoder passwordEncoder,
            RoleProvisioning roleProvisioning, OrganizationContext organizationContext,
            PlatformTransactionManager transactionManager) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.roleProvisioning = roleProvisioning;
        this.organizationContext = organizationContext;
        this.transactionManager = transactionManager;
        this.passwordHash = passwordEncoder.encode(PASSWORD);
    }

    public UUID organization() {
        UUID org = TestOrganizations.create(jdbc);
        roleProvisioning.provisionDefaultRoles(org);
        return org;
    }

    /** An active user (no forced password change) holding the given role codes of the organization. */
    public UUID user(UUID org, String email, String... roleCodes) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo User', ?, 'ACTIVE', false, now(), now())
                """).params(id, org, email, passwordHash).update();
        for (String code : roleCodes) {
            inOrg(org, () -> jdbc.sql("""
                    INSERT INTO hms.user_role (organization_id, user_id, role_id)
                    SELECT organization_id, ?, id FROM hms.role WHERE organization_id = ? AND code = ?
                    """).params(id, org, code).update());
        }
        return id;
    }

    public String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@demo-clinic.example";
    }

    /** A custom role holding exactly the given permissions. */
    public void role(UUID org, String code, String... permissions) {
        UUID roleId = UUID.randomUUID();
        inOrg(org, () -> {
            jdbc.sql("""
                    INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                    VALUES (?, ?, ?, ?, now(), now())
                    """).params(roleId, org, code, "Demo " + code).update();
            for (String permission : permissions) {
                jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) VALUES (?, ?, ?)")
                        .params(org, roleId, permission).update();
            }
            return null;
        });
    }

    public UUID roleId(UUID org, String code) {
        return inOrg(org, () -> jdbc.sql("SELECT id FROM hms.role WHERE organization_id = ? AND code = ?")
                .params(org, code).query(UUID.class).single());
    }

    public Session login(UUID userId, String email) throws Exception {
        return login(userId, email, PASSWORD);
    }

    public Session login(UUID userId, String email, String password) throws Exception {
        Cookie csrf = csrf();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andReturn();
        String pair = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().orElseThrow().split(";", 2)[0];
        return new Session(userId, email, new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1)), csrf);
    }

    /** Creates a user with the given roles and logs them in. */
    /**
     * A caller holding every catalogue permission, for tests that must assign clinical roles: even a system
     * administrator may only grant what it holds (api-design.md section 3), and SYSTEM_ADMIN lacks clinical ones.
     */
    public Session withAllPermissions(UUID org) throws Exception {
        String code = "ALL_PERMISSIONS";
        role(org, code, com.hms.identity.api.Permissions.ALL.toArray(String[]::new));
        return loggedIn(org, "everything", code);
    }

    public Session loggedIn(UUID org, String prefix, String... roleCodes) throws Exception {
        String email = uniqueEmail(prefix);
        return login(user(org, email, roleCodes), email);
    }

    public Cookie csrf() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return csrf;
    }

    public long version(UUID userId) {
        return jdbc.sql("SELECT version FROM hms.app_user WHERE id = ?").param(userId).query(Long.class).single();
    }

    public int sessionCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM hms.spring_session WHERE principal_name = ?")
                .param(userId.toString()).query(Integer.class).single();
    }

    public List<String> auditActions(UUID entityId) {
        return jdbc.sql("SELECT action FROM hms.audit_event WHERE entity_id = ? ORDER BY occurred_at, id")
                .param(entityId).query(String.class).list();
    }

    public <T> T inOrg(UUID org, java.util.function.Supplier<T> work) {
        return organizationContext.runAs(org, () -> new TransactionTemplate(transactionManager)
                .execute(status -> work.get()));
    }
}
