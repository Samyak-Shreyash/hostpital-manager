package com.hms.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.domain.NotFoundException;
import com.hms.support.PostgresIT;
import com.hms.support.TestOrganizations;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * [E1-6] Two-organization isolation harness (ADR-0010, CLAUDE.md section 7 "cross-organization access").
 *
 * <p>Layer 1, HTTP: every controller mapping is enumerated by reflection. A mapping with a path id must have a
 * {@link #CROSS_ORG_CASES} entry, which is called with organization B's id by a fully privileged user of
 * organization A and must answer 404 (and 2xx for A's own id, so the 404 is not for an unrelated reason).
 * A mapping without a path id must be listed in {@link #EXEMPT}. A new endpoint that is neither fails the build.
 *
 * <p>Layer 2, database: RLS hides another organization's rows even from queries that skip the app-level
 * {@code organization_id} filter, and every table with an {@code organization_id} column is under RLS unless
 * it is one of the ruled exclusions. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrganizationIsolationIT.ProbeController.class)
class OrganizationIsolationIT extends PostgresIT {

    private static final String PASSWORD = "Synthetic-Pass-123";
    private static final String SESSION_COOKIE = "HMSSESSION";
    private static final String PROBE = "/api/v1/test-isolation-probe/roles/{roleId}";

    /** Ruled exclusions from RLS (database-design.md section 14); app-level org scoping still applies to them. */
    private static final Set<String> RLS_EXCLUDED = Set.of(
            "organization", "app_user", "audit_event", "spring_session", "spring_session_attributes");

    /** Mappings without a path id: nothing to cross-check by id (list endpoints are scoped by the principal). */
    private static final Set<String> EXEMPT = Set.of(
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/logout",
            "GET /api/v1/auth/me",
            "POST /api/v1/auth/change-password",
            "GET /api/v1/users",
            "POST /api/v1/users",
            "GET /api/v1/roles",
            "POST /api/v1/roles",
            "GET /api/v1/permissions",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config",
            "GET /swagger-ui.html",
            "* /error");

    /** Mappings with a path id that are not organization data. Each needs a reason. */
    private static final Map<String, String> EXEMPT_WITH_ID = Map.of(
            "GET /v3/api-docs/{group}", "OpenAPI document group name, not an entity id",
            "GET /v3/api-docs.yaml/{group}", "OpenAPI document group name, not an entity id");

    /**
     * Cross-org cases, keyed "METHOD pattern". Each builds the request for a given entity of the fixture.
     * Later modules add their id-taking endpoints here (or the reflection test fails).
     */
    private static final String PATCH_BODY = "{\"version\":0,\"fullName\":\"Renamed User\"}";
    private static final String ROLE_PATCH_BODY = "{\"version\":0,\"name\":\"Renamed Role\"}";

    private static final Map<String, CrossOrgCase> CROSS_ORG_CASES = Map.of(
            "GET " + PROBE, new CrossOrgCase(org -> get(PROBE, org.roleId())),
            "GET /api/v1/users/{id}", new CrossOrgCase(org -> get("/api/v1/users/{id}", org.lifecycleUserId())),
            "PATCH /api/v1/users/{id}", new CrossOrgCase(org -> patch("/api/v1/users/{id}", org.patchUserId())
                    .contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY)),
            "POST /api/v1/users/{id}/activate",
            new CrossOrgCase(org -> post("/api/v1/users/{id}/activate", org.lifecycleUserId())),
            "POST /api/v1/users/{id}/deactivate",
            new CrossOrgCase(org -> post("/api/v1/users/{id}/deactivate", org.lifecycleUserId())),
            "POST /api/v1/users/{id}/reset-password",
            new CrossOrgCase(org -> post("/api/v1/users/{id}/reset-password", org.lifecycleUserId())),
            "PATCH /api/v1/roles/{id}", new CrossOrgCase(org -> patch("/api/v1/roles/{id}", org.roleId())
                    .contentType(MediaType.APPLICATION_JSON).content(ROLE_PATCH_BODY)));

    record CrossOrgCase(Function<OrgFixture, MockHttpServletRequestBuilder> request) {
    }

    /** One organization's ids that endpoints can be called with. Extend as modules add entities. */
    record OrgFixture(UUID organizationId, UUID roleId, UUID lifecycleUserId, UUID patchUserId) {
    }

    /**
     * Test-only endpoint that looks a role up by id <em>without</em> an organization filter, as a buggy repository
     * would. Only RLS stands between it and organization B's role, which is exactly what it demonstrates.
     */
    @RestController
    static class ProbeController {

        private final JdbcClient jdbc;
        private final PlatformTransactionManager transactionManager;

        ProbeController(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.transactionManager = transactionManager;
        }

        @GetMapping(PROBE)
        @PreAuthorize("hasAuthority('role:read')")
        String role(@PathVariable UUID roleId) {
            return new TransactionTemplate(transactionManager).execute(status ->
                    jdbc.sql("SELECT code FROM hms.role WHERE id = ?").param(roleId).query(String.class).optional())
                    .orElseThrow(() -> new NotFoundException("Role not found."));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    OrganizationContext organizationContext;

    @Autowired
    RoleProvisioning roleProvisioning;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private static String passwordHash;
    private OrgFixture orgA;
    private OrgFixture orgB;
    private String emailA;

    @BeforeEach
    void twoOrganizations() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        orgA = createOrganization();
        orgB = createOrganization();
        emailA = "isolation-" + UUID.randomUUID() + "@demo-clinic.example";
        UUID userA = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo Admin', ?, 'ACTIVE', false, now(), now())
                """)
                .params(userA, orgA.organizationId(), emailA, passwordHash)
                .update();
        // Every permission, so a 404 can never be a disguised authorization failure.
        inOrg(orgA.organizationId(), () -> {
            for (String permission : Permissions.ALL) {
                jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) VALUES (?, ?, ?)")
                        .params(orgA.organizationId(), orgA.roleId(), permission).update();
            }
            return jdbc.sql("INSERT INTO hms.user_role (organization_id, user_id, role_id) VALUES (?, ?, ?)")
                    .params(orgA.organizationId(), userA, orgA.roleId()).update();
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- Layer 1: HTTP, reflection-driven ----------------------------------------------------------------------

    @Test
    void everyEndpointIsCoveredByACrossOrgCaseOrExplicitlyExempt() {
        List<String> unaccounted = new ArrayList<>();
        for (String key : mappingKeys()) {
            boolean takesId = key.contains("{");
            boolean accounted = takesId
                    ? CROSS_ORG_CASES.containsKey(key) || EXEMPT_WITH_ID.containsKey(key)
                    : EXEMPT.contains(key) || EXEMPT.contains("* " + key.substring(key.indexOf(' ') + 1));
            if (!accounted) {
                unaccounted.add(key);
            }
        }
        assertThat(unaccounted)
                .as("endpoints with a path id need a CROSS_ORG_CASES entry; endpoints without one belong in EXEMPT")
                .isEmpty();
        assertThat(mappingKeys()).as("the harness must see the probe").containsAll(CROSS_ORG_CASES.keySet());
    }

    @Test
    void anotherOrganizationsIdAnswers404OnEveryIdEndpoint() throws Exception {
        Session session = login(emailA);
        for (Map.Entry<String, CrossOrgCase> entry : CROSS_ORG_CASES.entrySet()) {
            CrossOrgCase crossOrg = entry.getValue();
            mockMvc.perform(session.apply(crossOrg.request().apply(orgA)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s with the caller's own id (control)", entry.getKey()).isBetween(200, 299));
            mockMvc.perform(session.apply(crossOrg.request().apply(orgB)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s with another organization's id", entry.getKey()).isEqualTo(404));
        }
    }

    @Test
    void loginRunsInTheUsersOrganizationSoRlsProtectedRolesLoad() throws Exception {
        // The role tables are under RLS: permissions only load if login sets the context after the email lookup.
        Session session = login(emailA);
        mockMvc.perform(session.apply(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(Permissions.ROLE_READ));
    }

    // --- Layer 2: database -------------------------------------------------------------------------------------

    @Test
    void anotherOrganizationsRowsAreInvisibleEvenWithoutAnOrgFilter() {
        List<UUID> seenByA = inOrg(orgA.organizationId(), () ->
                jdbc.sql("SELECT DISTINCT organization_id FROM hms.role").query(UUID.class).list());
        assertThat(seenByA).containsExactly(orgA.organizationId());
        assertThat(inOrg(orgA.organizationId(), () -> jdbc.sql("SELECT count(*) FROM hms.role WHERE id = ?")
                .param(orgB.roleId()).query(Integer.class).single())).isZero();
        assertThat(inOrg(orgA.organizationId(), () -> jdbc.sql("UPDATE hms.role SET name = 'x' WHERE id = ?")
                .param(orgB.roleId()).update())).isZero();
        assertThat(inOrg(orgB.organizationId(), () -> jdbc.sql("SELECT name FROM hms.role WHERE id = ?")
                .param(orgB.roleId()).query(String.class).single())).isEqualTo("Demo Role");
    }

    @Test
    void writingARowForAnotherOrganizationIsRejected() {
        assertThatThrownBy(() -> inOrg(orgA.organizationId(), () -> jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, 'SMUGGLED', 'Smuggled', now(), now())""")
                .params(UUID.randomUUID(), orgB.organizationId()).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        assertThatThrownBy(() -> inOrg(orgA.organizationId(), () -> jdbc.sql("""
                INSERT INTO hms.document_sequence (organization_id, document_type, period_key, created_at, updated_at)
                VALUES (?, 'UHID', 'ALL', now(), now())""")
                .param(orgB.organizationId()).update()))
                .rootCause().hasMessageContaining("row-level security policy");
    }

    @Test
    void withoutAnOrganizationContextRlsTablesReturnNothing() {
        for (String table : rlsTables()) {
            int rows = new TransactionTemplate(transactionManager).execute(status ->
                    jdbc.sql("SELECT count(*) FROM hms." + table).query(Integer.class).single());
            assertThat(rows).as(table).isZero();
        }
    }

    @Test
    void everyOrgOwnedTableIsUnderForcedRlsExceptTheRuledExclusions() {
        List<String> orgOwned = jdbc.sql("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
                WHERE n.nspname = 'hms' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                """).query(String.class).list();
        Set<String> expected = new TreeSet<>(orgOwned);
        expected.removeAll(RLS_EXCLUDED);
        assertThat(expected).contains("role", "role_permission", "user_role", "document_sequence");
        assertThat(new TreeSet<>(rlsTables())).isEqualTo(expected);
    }

    @Test
    void contextIsTransactionLocalAndDoesNotLeak() {
        inOrg(orgA.organizationId(), () -> jdbc.sql("SELECT count(*) FROM hms.role").query(Integer.class).single());
        String after = new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT coalesce(current_setting('app.organization_id', true), '')").query(String.class)
                        .single());
        assertThat(after).isEmpty();
    }

    @Test
    void authenticatedUserCannotSwitchToAnotherOrganization() {
        authenticateAs(orgA.organizationId());
        assertThat(organizationContext.current()).contains(orgA.organizationId());
        assertThatThrownBy(() -> organizationContext.runAs(orgB.organizationId(), () -> 1))
                .isInstanceOf(IllegalStateException.class);
        // The principal's own organization is applied to transactions without any explicit call.
        List<UUID> visible = new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT DISTINCT organization_id FROM hms.role").query(UUID.class).list());
        assertThat(visible).containsExactly(orgA.organizationId());
    }

    @Test
    void systemJobsWithoutAPrincipalKeepWorking() {
        // Audit partition maintenance runs with no principal and no organization (SECURITY DEFINER function).
        assertThat(organizationContext.current()).isEmpty();
        Integer created = jdbc.sql("SELECT hms.ensure_audit_event_partitions(3)").query(Integer.class).single();
        assertThat(created).isNotNull();
    }

    // --- Fixtures ----------------------------------------------------------------------------------------------

    private OrgFixture createOrganization() {
        UUID organizationId = TestOrganizations.create(jdbc);
        roleProvisioning.provisionDefaultRoles(organizationId); // what org creation must do (Task 5 bootstrap)
        UUID roleId = UUID.randomUUID();
        inOrg(organizationId, () -> jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, 'ALL_ACCESS', 'Demo Role', now(), now())""")
                .params(roleId, organizationId).update());
        // Users other than the caller: lifecycle endpoints are idempotent so they can be called in any order;
        // the patch target is touched by the PATCH case only, so its version stays 0.
        UUID lifecycleUserId = insertUser(organizationId);
        UUID patchUserId = insertUser(organizationId);
        return new OrgFixture(organizationId, roleId, lifecycleUserId, patchUserId);
    }

    private UUID insertUser(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo Target', ?, 'ACTIVE', false, now(), now())
                """)
                .params(id, organizationId, "target-" + id + "@demo-clinic.example", passwordHash).update();
        return id;
    }

    private <T> T inOrg(UUID organizationId, Supplier<T> work) {
        return organizationContext.runAs(organizationId,
                () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private List<String> rlsTables() {
        return jdbc.sql("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'hms' AND c.relrowsecurity AND c.relforcerowsecurity
                  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                """).query(String.class).list();
    }

    private void authenticateAs(UUID organizationId) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(UUID.randomUUID(), organizationId,
                "someone@demo-clinic.example", "Demo", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private Set<String> mappingKeys() {
        Set<String> keys = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            Set<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of("*")
                    : info.getMethodsCondition().getMethods().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet());
            for (String pattern : patterns) {
                for (String method : methods) {
                    keys.add(method + " " + pattern);
                }
            }
        }
        return keys;
    }

    /** A logged-in session plus the CSRF token, applied to any request. */
    private record Session(Cookie session, Cookie csrf) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private Session login(String email) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andReturn();
        String pair = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().orElseThrow().split(";", 2)[0];
        return new Session(new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1)), csrf);
    }
}
