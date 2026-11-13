package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/** [E1-7] Role and permission endpoints (api-design.md section 3). Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class RoleManagementIT extends PostgresIT {

    private static final String ROLES = "/api/v1/roles";

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
    private AdminApiFixture.Session admin;

    @BeforeEach
    void organizationWithAnAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "admin", "SYSTEM_ADMIN");
    }

    @Test
    void endpointsNeedASessionAndTheRightPermission() throws Exception {
        Cookie csrf = fx.csrf();
        UUID id = fx.roleId(org, "NURSE");
        for (MockHttpServletRequestBuilder request : List.of(
                get(ROLES), get("/api/v1/permissions"), post(ROLES), patch(ROLES + "/" + id))) {
            mockMvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}")
                            .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                    .andExpect(status().isUnauthorized());
        }
        fx.role(org, "ONLY_READ_ROLES", "role:read");
        fx.role(org, "ONLY_MANAGE_ROLES", "role:manage");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "ONLY_READ_ROLES");
        AdminApiFixture.Session manager = fx.loggedIn(org, "manager", "ONLY_MANAGE_ROLES");
        AdminApiFixture.Session nobody = fx.loggedIn(org, "reception", "RECEPTIONIST");
        String createBody = "{\"code\":\"NEVER_CREATED\",\"name\":\"Never\",\"permissionCodes\":[]}";
        String patchBody = "{\"version\":0,\"name\":\"Never Renamed\"}";

        mockMvc.perform(reader.apply(get(ROLES))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get("/api/v1/permissions"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(createBody)))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch(ROLES + "/" + id).contentType(MediaType.APPLICATION_JSON)
                .content(patchBody))).andExpect(status().isForbidden());
        mockMvc.perform(manager.apply(get(ROLES))).andExpect(status().isForbidden());
        mockMvc.perform(manager.apply(get("/api/v1/permissions"))).andExpect(status().isForbidden());
        for (AdminApiFixture.Session who : List.of(nobody)) {
            mockMvc.perform(who.apply(get(ROLES))).andExpect(status().isForbidden());
            mockMvc.perform(who.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(createBody)))
                    .andExpect(status().isForbidden());
            mockMvc.perform(who.apply(patch(ROLES + "/" + id).contentType(MediaType.APPLICATION_JSON)
                    .content(patchBody))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.sql("SELECT count(*) FROM hms.role WHERE organization_id = ? AND code = 'NEVER_CREATED'")
                .param(org).query(Integer.class).single()).isZero();
        // The manager can manage roles, so the 403s above were about the missing permission only.
        mockMvc.perform(manager.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(createBody)))
                .andExpect(status().isCreated());
    }

    @Test
    void listsRolesWithTheirPermissionsAndTheCatalogue() throws Exception {
        UUID foreignOrg = fx.organization();
        fx.role(foreignOrg, "FOREIGN_ONLY", "patient:read");

        mockMvc.perform(admin.apply(get(ROLES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code == 'NURSE')].permissions[*]").value(hasItem("bed:assign")))
                .andExpect(jsonPath("$[?(@.code == 'FOREIGN_ONLY')]").isEmpty())
                .andExpect(jsonPath("$.length()").value(6));
        mockMvc.perform(admin.apply(get("/api/v1/permissions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(Permissions.ALL.size()))
                .andExpect(jsonPath("$").value(hasItem("user:reset-password")));
    }

    @Test
    void createsACustomRoleAndRejectsDuplicatesUnknownPermissionsAndBadInput() throws Exception {
        AdminApiFixture.Session everything = fx.withAllPermissions(org);
        String body = "{\"code\":\"LAB_TECH\",\"name\":\"Lab Technician\",\"permissionCodes\":"
                + "[\"patient:read\",\"patient:search\"]}";
        mockMvc.perform(everything.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("LAB_TECH"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.version").value(0));
        UUID id = fx.roleId(org, "LAB_TECH");
        assertThat(fx.auditActions(id)).containsExactly("ROLE_CREATED");

        mockMvc.perform(everything.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_ROLE_CODE"));
        mockMvc.perform(everything.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"BAD_PERM\",\"name\":\"X\",\"permissionCodes\":[\"made:up\"]}")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        for (String invalid : List.of(
                "{\"code\":\"lower\",\"name\":\"X\",\"permissionCodes\":[]}",
                "{\"code\":\"OK_CODE\",\"name\":\"\",\"permissionCodes\":[]}",
                "{\"code\":\"OK_CODE\",\"name\":\"X\"}")) {
            mockMvc.perform(everything.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(invalid)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        // The same code is free in another organization.
        UUID otherOrg = fx.organization();
        AdminApiFixture.Session otherEverything = fx.withAllPermissions(otherOrg);
        mockMvc.perform(otherEverything.apply(post(ROLES).contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void updateChangesNameAndPermissionsEndsHoldersSessionsAndIsAudited() throws Exception {
        AdminApiFixture.Session everything = fx.withAllPermissions(org);
        fx.role(org, "LAB_TECH", "patient:read", "patient:search");
        UUID roleId = fx.roleId(org, "LAB_TECH");
        String email = fx.uniqueEmail("lab");
        UUID holder = fx.user(org, email, "LAB_TECH");
        AdminApiFixture.Session holderSession = fx.login(holder, email);

        mockMvc.perform(everything.apply(patch(ROLES + "/" + roleId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Lab Tech\",\"permissionCodes\":[\"patient:read\","
                                + "\"report:clinical\"]}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Lab Tech"))
                .andExpect(jsonPath("$.permissions.length()").value(2))
                .andExpect(jsonPath("$.permissions").value(hasItem("report:clinical")))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(fx.sessionCount(holder)).isZero();
        mockMvc.perform(get("/api/v1/auth/me").cookie(holderSession.session())).andExpect(status().isUnauthorized());
        String me = mockMvc.perform(fx.login(holder, email).apply(get("/api/v1/auth/me"))).andReturn().getResponse()
                .getContentAsString();
        assertThat((List<String>) JsonPath.read(me, "$.permissions")).containsExactly("patient:read", "report:clinical");
        assertThat(jdbc.sql("""
                SELECT string_agg(changed_fields::text, ',') FROM hms.audit_event
                WHERE entity_id = ? AND action = 'ROLE_UPDATED'""").param(roleId).query(String.class).single())
                .contains("name").contains("permissions");

        mockMvc.perform(everything.apply(patch(ROLES + "/" + roleId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Stale\"}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        mockMvc.perform(everything.apply(patch(ROLES + "/" + roleId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"permissionCodes\":[\"made:up\"]}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("UNKNOWN_PERMISSION"));
        mockMvc.perform(everything.apply(patch(ROLES + "/" + roleId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1}")))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(everything.apply(patch(ROLES + "/" + roleId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"No version\"}")))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anotherOrganizationsRoleIs404() throws Exception {
        UUID foreignOrg = fx.organization();
        UUID foreignRole = fx.roleId(foreignOrg, "NURSE");
        mockMvc.perform(admin.apply(patch(ROLES + "/" + foreignRole).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Hijacked\"}")))
                .andExpect(status().isNotFound());
        assertThat(fx.inOrg(foreignOrg, () -> jdbc.sql("SELECT name FROM hms.role WHERE id = ?").param(foreignRole)
                .query(String.class).single())).isEqualTo("Nurse");
    }

    @Test
    void thePermissionsOfTheLastAdministratorRoleCannotBeStripped() throws Exception {
        UUID adminRole = fx.roleId(org, "SYSTEM_ADMIN");
        // Dropping role:manage would leave nobody able to manage roles; user:update likewise for users.
        mockMvc.perform(admin.apply(patch(ROLES + "/" + adminRole).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"permissionCodes\":[\"user:read\",\"role:read\"]}")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ADMINISTRATOR"));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.role_permission WHERE role_id = ?")
                .param(adminRole).query(Integer.class).single())).isGreaterThan(5);
        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);

        // A rename alone is fine, and so is a change that keeps both permissions; the caller keeps their session.
        mockMvc.perform(admin.apply(patch(ROLES + "/" + adminRole).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"permissionCodes\":[\"user:update\",\"role:manage\"]}")))
                .andExpect(status().isOk());
        assertThat(fx.sessionCount(admin.userId())).isEqualTo(1);
        mockMvc.perform(get("/api/v1/auth/me").cookie(admin.session())).andExpect(status().isOk());
    }
}
