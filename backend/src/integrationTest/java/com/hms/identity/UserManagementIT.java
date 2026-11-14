package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.RoleProvisioning;
import com.hms.identity.domain.PasswordPolicy;
import com.hms.identity.infrastructure.UserAdminRepository;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E1-7] User management API (api-design.md section 3). Synthetic data only. Negative tests use a valid CSRF
 * header and a real session, so a 401/403/404 can only come from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UserManagementIT extends PostgresIT {

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

    @Autowired
    UserAdminRepository userAdmin;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session admin;

    @BeforeEach
    void organizationWithAnAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
    }

    // --- 401 / 403 -----------------------------------------------------------------------------------------------

    @Test
    void everyEndpointNeedsASession() throws Exception {
        Cookie csrf = fx.csrf();
        UUID id = UUID.randomUUID();
        for (MockHttpServletRequestBuilder request : List.of(
                get(USERS), post(USERS), get(USERS + "/" + id), patch(USERS + "/" + id),
                post(USERS + "/" + id + "/activate"), post(USERS + "/" + id + "/deactivate"),
                post(USERS + "/" + id + "/reset-password"))) {
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void everyEndpointNeedsItsOwnPermission() throws Exception {
        UUID target = fx.user(org, fx.uniqueEmail("target"), "RECEPTIONIST");
        String id = target.toString();
        String createBody = createBody(fx.uniqueEmail("never"), "Never Created", "NURSE");
        String patchBody = "{\"version\":0,\"fullName\":\"Never Renamed\"}";
        List<String> all = List.of("user:read", "user:create", "user:update", "user:reset-password");

        record Endpoint(String permission, java.util.function.Supplier<MockHttpServletRequestBuilder> request) {
        }
        List<Endpoint> endpoints = List.of(
                new Endpoint("user:read", () -> get(USERS)),
                new Endpoint("user:read", () -> get(USERS + "/" + id)),
                new Endpoint("user:create", () -> post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody)),
                new Endpoint("user:update", () -> patch(USERS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content(patchBody)),
                new Endpoint("user:update", () -> post(USERS + "/" + id + "/activate")),
                new Endpoint("user:update", () -> post(USERS + "/" + id + "/deactivate")),
                new Endpoint("user:reset-password", () -> post(USERS + "/" + id + "/reset-password")));
        // For each permission, a user who holds every other user permission (and a session of their own) is refused.
        for (String missing : all) {
            String roleCode = "ALL_BUT_" + missing.replaceAll("[^a-z]", "_").toUpperCase();
            fx.role(org, roleCode, all.stream().filter(p -> !p.equals(missing)).toArray(String[]::new));
            AdminApiFixture.Session session = fx.loggedIn(org, "but", roleCode);
            for (Endpoint endpoint : endpoints) {
                if (endpoint.permission().equals(missing)) {
                    mockMvc.perform(session.apply(endpoint.request().get())).andExpect(status().isForbidden())
                            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
                }
            }
        }
        AdminApiFixture.Session nobody = fx.loggedIn(org, "reception", "RECEPTIONIST");
        for (Endpoint endpoint : endpoints) {
            mockMvc.perform(nobody.apply(endpoint.request().get())).andExpect(status().isForbidden());
        }
        // Nothing happened, and the administrator can use the same endpoints.
        assertThat(statusOf(target)).isEqualTo("ACTIVE");
        assertThat(jdbc.sql("SELECT full_name FROM hms.app_user WHERE id = ?").param(target).query(String.class)
                .single()).isEqualTo("Demo User");
        mockMvc.perform(admin.apply(get(USERS))).andExpect(status().isOk());
        mockMvc.perform(admin.apply(patch(USERS + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))).andExpect(status().isOk());
    }

    // --- create --------------------------------------------------------------------------------------------------

    @Test
    void createReturnsATemporaryPasswordOnceAndTheUserMustChangeIt(CapturedOutput output) throws Exception {
        AdminApiFixture.Session everything = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        String email = "Dr.Rao-" + UUID.randomUUID() + "@Demo-Clinic.Example";
        ResultActions created = mockMvc.perform(everything.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email, "Dr. A. Rao", "DOCTOR"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.roleCodes[0]").value("DOCTOR"))
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        String body = created.andReturn().getResponse().getContentAsString();
        String temporaryPassword = JsonPath.read(body, "$.temporaryPassword");
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        assertThat(PasswordPolicy.check(temporaryPassword, email)).isEmpty();
        // It works, once, and the user is told to change it.
        Cookie csrf = fx.csrf();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email.toLowerCase() + "\",\"password\":\"" + temporaryPassword + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true))
                .andExpect(jsonPath("$.user.roles[0]").value("DOCTOR"));

        // Not retrievable again, not in the audit trail, not in the logs, not stored in clear text.
        mockMvc.perform(everything.apply(get(USERS + "/" + id)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.temporaryPassword").doesNotExist());
        List<String> audit = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(fx.auditActions(id)).contains("USER_CREATED");
        assertThat(String.join("\n", audit)).contains("email", "fullName").doesNotContain(temporaryPassword);
        assertThat(jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(id).query(String.class)
                .single()).isNotEqualTo(temporaryPassword).startsWith("$2");
        assertThat(output.getAll()).doesNotContain(temporaryPassword);
    }

    @Test
    void duplicateEmailIsRejectedAcrossOrganizationsAndIgnoringCase() throws Exception {
        AdminApiFixture.Session everything = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        String email = fx.uniqueEmail("dup");
        mockMvc.perform(everything.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email, "First", "NURSE"))))
                .andExpect(status().isCreated());
        mockMvc.perform(everything.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email.toUpperCase(), "Second", "NURSE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));

        UUID otherOrg = fx.organization();
        AdminApiFixture.Session otherEverything = fx.loggedIn(otherOrg, "admin", "SYSTEM_ADMIN");
        mockMvc.perform(otherEverything.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(email, "Third", "NURSE"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
        assertThat(jdbc.sql("SELECT count(*) FROM hms.app_user WHERE email = ?").param(email.toLowerCase())
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void createValidatesInputAndRoles() throws Exception {
        for (String invalid : List.of(
                "{\"email\":\"not-an-email\",\"fullName\":\"X\",\"roleCodes\":[]}",
                "{\"email\":\"" + fx.uniqueEmail("v") + "\",\"fullName\":\"\",\"roleCodes\":[]}",
                "{\"email\":\"" + fx.uniqueEmail("v") + "\",\"fullName\":\"X\"}",
                "{\"email\":\"" + fx.uniqueEmail("v") + "\",\"fullName\":\"X\",\"roleCodes\":[\"\"]}")) {
            mockMvc.perform(admin.apply(post(USERS).contentType(MediaType.APPLICATION_JSON).content(invalid)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(admin.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(fx.uniqueEmail("v"), "X", "NO_SUCH_ROLE"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));
        // Another organization's custom role is unknown here, not leaked.
        UUID otherOrg = fx.organization();
        fx.role(otherOrg, "OTHER_ORG_ONLY", "patient:read");
        mockMvc.perform(admin.apply(post(USERS).contentType(MediaType.APPLICATION_JSON)
                        .content(createBody(fx.uniqueEmail("v"), "X", "OTHER_ORG_ONLY"))))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));
    }

    // --- list / get ----------------------------------------------------------------------------------------------

    @Test
    void listFiltersPaginatesAndStaysInTheCallersOrganization() throws Exception {
        UUID nurse = fx.user(org, "nina-" + UUID.randomUUID() + "@demo-clinic.example", "NURSE");
        fx.user(org, fx.uniqueEmail("zed"), "DOCTOR");
        jdbc.sql("UPDATE hms.app_user SET full_name = 'Nina Nurse' WHERE id = ?").param(nurse).update();
        UUID inactive = fx.user(org, fx.uniqueEmail("gone"), "NURSE");
        jdbc.sql("UPDATE hms.app_user SET status = 'INACTIVE' WHERE id = ?").param(inactive).update();
        UUID otherOrg = fx.organization();
        fx.user(otherOrg, fx.uniqueEmail("elsewhere"), "NURSE");

        mockMvc.perform(admin.apply(get(USERS).param("role", "NURSE").param("status", "ACTIVE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].id").value(nurse.toString()))
                .andExpect(jsonPath("$.items[0].roleCodes[0]").value("NURSE"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
        mockMvc.perform(admin.apply(get(USERS).param("q", "nina nu")))
                .andExpect(jsonPath("$.items[0].id").value(nurse.toString()))
                .andExpect(jsonPath("$.totalItems").value(1));
        mockMvc.perform(admin.apply(get(USERS).param("status", "INACTIVE")))
                .andExpect(jsonPath("$.items[0].id").value(inactive.toString()));
        // LIKE wildcards in the search text are literal.
        mockMvc.perform(admin.apply(get(USERS).param("q", "%")))
                .andExpect(jsonPath("$.totalItems").value(0));
        // This organization only: admin, nurse, doctor, inactive.
        mockMvc.perform(admin.apply(get(USERS).param("size", "2").param("page", "1")))
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(1));

        mockMvc.perform(admin.apply(get(USERS).param("size", "101"))).andExpect(status().isUnprocessableContent());
        mockMvc.perform(admin.apply(get(USERS).param("status", "BOGUS"))).andExpect(status().isUnprocessableContent());
    }

    @Test
    void getReturnsTheUserIsAuditedAndAnotherOrganizationsUserIs404() throws Exception {
        UUID doctor = fx.user(org, fx.uniqueEmail("doc"), "DOCTOR");
        mockMvc.perform(admin.apply(get(USERS + "/" + doctor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(doctor.toString()))
                .andExpect(jsonPath("$.roleCodes[0]").value("DOCTOR"))
                .andExpect(jsonPath("$.version").value(0));
        assertThat(fx.auditActions(doctor)).containsExactly("USER_VIEWED");

        UUID foreign = fx.user(fx.organization(), fx.uniqueEmail("foreign"), "DOCTOR");
        mockMvc.perform(admin.apply(get(USERS + "/" + foreign))).andExpect(status().isNotFound());
        mockMvc.perform(admin.apply(get(USERS + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(fx.auditActions(foreign)).isEmpty();
    }

    // --- update --------------------------------------------------------------------------------------------------

    @Test
    void updateRenamesReplacesRolesEndsTheUsersSessionsAndIsAudited() throws Exception {
        AdminApiFixture.Session everything = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
        String email = fx.uniqueEmail("doc");
        UUID doctor = fx.user(org, email, "DOCTOR");
        AdminApiFixture.Session doctorSession = fx.login(doctor, email);
        assertThat(fx.sessionCount(doctor)).isEqualTo(1);
        long versionBefore = fx.version(doctor);

        mockMvc.perform(everything.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(doctor)
                                + ",\"fullName\":\"  Dr. B. Rao \",\"roleCodes\":[\"NURSE\",\"RECEPTIONIST\"]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Dr. B. Rao"))
                .andExpect(jsonPath("$.roleCodes").value(hasItems("NURSE", "RECEPTIONIST")))
                .andExpect(jsonPath("$.roleCodes.length()").value(2))
                .andExpect(jsonPath("$.version").value(versionBefore + 1));

        // Roles apply at once: the old session is gone, a fresh login has the new roles.
        assertThat(fx.sessionCount(doctor)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(doctorSession.session())).andExpect(status().isUnauthorized());
        mockMvc.perform(fx.login(doctor, email).apply(get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.user.roles.length()").value(2));
        assertThat(jdbc.sql("""
                SELECT string_agg(changed_fields::text, ',') FROM hms.audit_event
                WHERE entity_id = ? AND action = 'USER_UPDATED'""").param(doctor).query(String.class).single())
                .contains("fullName").contains("roles");
    }

    @Test
    void updateRejectsAStaleVersionUnknownRolesAndEmptyChanges() throws Exception {
        UUID doctor = fx.user(org, fx.uniqueEmail("doc"), "DOCTOR");
        mockMvc.perform(admin.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":7,\"fullName\":\"Someone\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        mockMvc.perform(admin.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"roleCodes\":[\"NO_SUCH_ROLE\"]}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_ROLE"));
        mockMvc.perform(admin.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(admin.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"No version\"}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(admin.apply(patch(USERS + "/" + doctor).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"fullName\":\"   \"}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        UUID foreign = fx.user(fx.organization(), fx.uniqueEmail("foreign"), "DOCTOR");
        mockMvc.perform(admin.apply(patch(USERS + "/" + foreign).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"fullName\":\"Hijacked\"}")))
                .andExpect(status().isNotFound());
        assertThat(jdbc.sql("SELECT full_name FROM hms.app_user WHERE id = ?").param(foreign).query(String.class)
                .single()).isEqualTo("Demo User");
    }

    // --- activate / deactivate -----------------------------------------------------------------------------------

    @Test
    void deactivateEndsSessionsBlocksLoginAndActivateRestoresIt() throws Exception {
        String email = fx.uniqueEmail("nurse");
        UUID nurse = fx.user(org, email, "NURSE");
        AdminApiFixture.Session first = fx.login(nurse, email);
        fx.login(nurse, email);
        assertThat(fx.sessionCount(nurse)).isEqualTo(2);

        mockMvc.perform(admin.apply(post(USERS + "/" + nurse + "/deactivate")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INACTIVE"));
        assertThat(fx.sessionCount(nurse)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(first.session())).andExpect(status().isUnauthorized());
        Cookie csrf = fx.csrf();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + AdminApiFixture.PASSWORD + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
        // Idempotent: a second deactivate changes nothing and is not audited again.
        mockMvc.perform(admin.apply(post(USERS + "/" + nurse + "/deactivate"))).andExpect(status().isOk());
        assertThat(fx.auditActions(nurse).stream().filter("USER_DEACTIVATED"::equals).count()).isEqualTo(1);

        mockMvc.perform(admin.apply(post(USERS + "/" + nurse + "/activate")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        fx.login(nurse, email);
        assertThat(fx.auditActions(nurse)).contains("USER_DEACTIVATED", "USER_ACTIVATED");
    }

    @Test
    void anAdministratorCannotDeactivateThemselves() throws Exception {
        UUID second = fx.user(org, fx.uniqueEmail("admin2"), "SYSTEM_ADMIN"); // so "last admin" is not the reason
        assertThat(second).isNotNull();
        mockMvc.perform(admin.apply(post(USERS + "/" + admin.userId() + "/deactivate")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_SELF"));
        assertThat(statusOf(admin.userId())).isEqualTo("ACTIVE");
        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);
    }

    @Test
    void theLastActiveAdministratorCannotBeDeactivatedDemotedOrStrippedOfPermissions() throws Exception {
        // Can manage users but not roles, so it is not itself an administrator and can act on the real one.
        // It also holds the NURSE permissions, so assigning NURSE is not an escalation (api-design.md section 3).
        fx.role(org, "PLAIN_USER_MANAGER", "user:update", "user:read", "patient:read",
                "patient:update-clinical", "observation:write", "bed:assign", "admission:read");
        AdminApiFixture.Session manager = fx.loggedIn(org, "manager", "PLAIN_USER_MANAGER");

        mockMvc.perform(manager.apply(post(USERS + "/" + admin.userId() + "/deactivate")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
        mockMvc.perform(manager.apply(patch(USERS + "/" + admin.userId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + fx.version(admin.userId()) + ",\"roleCodes\":[\"NURSE\"]}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
        assertThat(statusOf(admin.userId())).isEqualTo("ACTIVE");
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.user_role WHERE user_id = ?")
                .param(admin.userId()).query(Integer.class).single())).isEqualTo(1);
        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);

        // With a second administrator the first can be deactivated.
        UUID second = fx.user(org, fx.uniqueEmail("admin2"), "SYSTEM_ADMIN");
        mockMvc.perform(manager.apply(post(USERS + "/" + admin.userId() + "/deactivate"))).andExpect(status().isOk());
        // ...but now the second is the last one.
        mockMvc.perform(manager.apply(post(USERS + "/" + second + "/deactivate")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
    }

    @Test
    void twoConcurrentDeactivationsCannotLeaveNoAdministrator() throws Exception {
        String emailB = fx.uniqueEmail("admin-b");
        UUID adminB = fx.user(org, emailB, "SYSTEM_ADMIN");
        AdminApiFixture.Session sessionB = fx.login(adminB, emailB);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> mockMvc.perform(admin.apply(post(USERS + "/" + adminB + "/deactivate")))
                            .andReturn().getResponse().getStatus(),
                    () -> mockMvc.perform(sessionB.apply(post(USERS + "/" + admin.userId() + "/deactivate")))
                            .andReturn().getResponse().getStatus());
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                results.add(future.get());
            }
            assertThat(results).contains(200).doesNotContain(500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM hms.app_user WHERE organization_id = ? AND status = 'ACTIVE'")
                .param(org).query(Integer.class).single()).isEqualTo(1);
    }

    // --- reset password ------------------------------------------------------------------------------------------

    @Test
    void resetPasswordIssuesATemporaryPasswordOnceEndsSessionsAndUnlocks(CapturedOutput output) throws Exception {
        String email = fx.uniqueEmail("nurse");
        UUID nurse = fx.user(org, email, "NURSE");
        AdminApiFixture.Session old = fx.login(nurse, email);
        jdbc.sql("UPDATE hms.app_user SET failed_login_count = 5, locked_until = now() + interval '10 minutes' "
                + "WHERE id = ?").param(nurse).update();
        String oldHash = jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(nurse)
                .query(String.class).single();

        ResultActions reset = mockMvc.perform(admin.apply(post(USERS + "/" + nurse + "/reset-password")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.mustChangePassword").value(true))
                .andExpect(jsonPath("$.temporaryPassword").isNotEmpty());
        String temporaryPassword = JsonPath.read(reset.andReturn().getResponse().getContentAsString(),
                "$.temporaryPassword");

        assertThat(PasswordPolicy.check(temporaryPassword, email)).isEmpty();
        assertThat(fx.sessionCount(nurse)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(old.session())).andExpect(status().isUnauthorized());
        // The old password is dead, the temporary one works (the lockout was cleared) and must be changed.
        Cookie csrf = fx.csrf();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + temporaryPassword + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.mustChangePassword").value(true));
        assertThat(jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(nurse).query(String.class)
                .single()).isNotEqualTo(oldHash);

        String auditText = String.join("\n", jdbc.sql(
                "SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(nurse).query(String.class).list());
        assertThat(fx.auditActions(nurse)).contains("USER_PASSWORD_RESET");
        assertThat(auditText).contains("passwordHash").doesNotContain(temporaryPassword, oldHash);
        assertThat(output.getAll()).doesNotContain(temporaryPassword);
    }

    @Test
    void resettingYourOwnPasswordKeepsYourSessionButEndsTheOthers() throws Exception {
        AdminApiFixture.Session other = fx.login(admin.userId(), admin.email());
        assertThat(fx.sessionCount(admin.userId())).isEqualTo(2);

        mockMvc.perform(admin.apply(post(USERS + "/" + admin.userId() + "/reset-password")))
                .andExpect(status().isOk());

        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/auth/me").cookie(other.session())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(admin.session())).andExpect(status().isOk());
    }

    @Test
    void selfServiceChangePasswordEndsTheOtherSessionsButKeepsTheCurrentOne() throws Exception {
        AdminApiFixture.Session other = fx.login(admin.userId(), admin.email());
        mockMvc.perform(admin.apply(post("/api/v1/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + AdminApiFixture.PASSWORD
                                + "\",\"newPassword\":\"Synthetic-New-Pass-2\"}")))
                .andExpect(status().isNoContent());

        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/auth/me").cookie(other.session())).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(admin.session())).andExpect(status().isOk());
    }

    // --- cross-organization --------------------------------------------------------------------------------------

    @Test
    void anotherOrganizationsUserIs404OnEveryIdEndpointAndNothingChanges() throws Exception {
        UUID foreign = fx.user(fx.organization(), fx.uniqueEmail("foreign"), "NURSE");
        String hash = jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(foreign)
                .query(String.class).single();
        for (MockHttpServletRequestBuilder request : List.of(
                get(USERS + "/" + foreign),
                patch(USERS + "/" + foreign).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"fullName\":\"X\"}"),
                post(USERS + "/" + foreign + "/activate"),
                post(USERS + "/" + foreign + "/deactivate"),
                post(USERS + "/" + foreign + "/reset-password"))) {
            mockMvc.perform(admin.apply(request)).andExpect(status().isNotFound());
        }
        assertThat(jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(foreign)
                .query(String.class).single()).isEqualTo(hash);
        assertThat(statusOf(foreign)).isEqualTo("ACTIVE");
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private String statusOf(UUID userId) {
        return jdbc.sql("SELECT status FROM hms.app_user WHERE id = ?").param(userId).query(String.class).single();
    }

    private static String createBody(String email, String fullName, String... roleCodes) {
        String roles = String.join("\",\"", roleCodes);
        return "{\"email\":\"" + email + "\",\"fullName\":\"" + fullName + "\",\"roleCodes\":[\"" + roles + "\"]}";
    }
}
