package com.hms.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E2-2] Departments and specialties (api-design.md section 4); both kinds share one implementation and one test
 * set. Synthetic data only. Negative tests use a valid CSRF header and a real session, so a 401/403 can only come
 * from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogIT extends PostgresIT {

    private static final String KINDS = """
            /api/v1/departments, DEPARTMENT, department, DUPLICATE_DEPARTMENT_CODE
            /api/v1/specialties, SPECIALTY, specialty, DUPLICATE_SPECIALTY_CODE
            """;

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
    void organizationWithAFacilityAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        // The default Facility Administrator template holds catalog:* (PRD persona), so it is the realistic caller.
        admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
    }

    private String create(AdminApiFixture.Session session, String url, String code, String name) throws Exception {
        return mockMvc.perform(session.apply(post(url)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void endpointsNeedASession(String url, String entity, String table, String duplicateCode) throws Exception {
        String id = JsonPath.read(create(admin, url, "CARDIO", "Cardiology"), "$.id");
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"XX\",\"name\":\"X\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch(url + "/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"X\"}").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void readNeedsCatalogReadAndWritesNeedCatalogManage(String url, String entity, String table,
            String duplicateCode) throws Exception {
        fx.role(org, "READ_ONLY", "catalog:read");
        fx.role(org, "MANAGE_ONLY", "catalog:manage");
        fx.role(org, "NOTHING_USEFUL", "org:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READ_ONLY");
        AdminApiFixture.Session manager = fx.loggedIn(org, "manager", "MANAGE_ONLY");
        AdminApiFixture.Session outsider = fx.loggedIn(org, "outsider", "NOTHING_USEFUL");
        String id = JsonPath.read(create(admin, url, "CARDIO", "Cardiology"), "$.id");
        String createBody = "{\"code\":\"NEWCODE\",\"name\":\"New Entry\"}";
        String patchBody = "{\"version\":0,\"name\":\"Renamed\"}";

        mockMvc.perform(reader.apply(get(url))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(url)).contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)).andExpect(status().isForbidden());

        mockMvc.perform(manager.apply(get(url))).andExpect(status().isForbidden());
        mockMvc.perform(outsider.apply(get(url))).andExpect(status().isForbidden());
        mockMvc.perform(outsider.apply(post(url)).contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(manager.apply(post(url)).contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated());
        mockMvc.perform(manager.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content(patchBody)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void createReturnsTheDocumentedShapeAndAuditsFieldNamesOnly(String url, String entity, String table,
            String duplicateCode) throws Exception {
        mockMvc.perform(admin.apply(post(url)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CARDIO\",\"name\":\"  Cardiology  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("CARDIO")))
                .andExpect(jsonPath("$.name", is("Cardiology")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.id").isNotEmpty());

        UUID id = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms." + table + " WHERE organization_id = ? AND code = 'CARDIO'")
                .param(org).query(UUID.class).single());
        assertThat(fx.auditActions(id)).containsExactly(entity + "_CREATED");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ?")
                .param(id).query(String.class).single();
        assertThat(fields).contains("code", "name", "active").doesNotContain("Cardiology", "CARDIO");
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void codeIsUniquePerOrganizationButReusableInAnotherOne(String url, String entity, String table,
            String duplicateCode) throws Exception {
        create(admin, url, "CARDIO", "Cardiology");

        mockMvc.perform(admin.apply(post(url)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CARDIO\",\"name\":\"Another Name\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is(duplicateCode)));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms." + table + " WHERE organization_id = ?").param(org)
                .query(Integer.class).single())).isEqualTo(1);

        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        create(otherAdmin, url, "CARDIO", "Cardiology");
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void invalidCreateInputIsRejected(String url, String entity, String table, String duplicateCode)
            throws Exception {
        for (String body : List.of(
                "{\"code\":\"cardio\",\"name\":\"Lower Case Code\"}",
                "{\"code\":\"C\",\"name\":\"Too Short\"}",
                "{\"code\":\"ABCDEFGHIJKLMNOPQRSTU\",\"name\":\"Too Long Code\"}",
                "{\"code\":\"CARD-IO\",\"name\":\"Bad Character\"}",
                "{\"code\":\"CARDIO\",\"name\":\"   \"}",
                "{\"code\":\"CARDIO\"}",
                "{\"name\":\"No Code\"}",
                "{\"code\":\"CARDIO\",\"name\":\"" + "x".repeat(101) + "\"}")) {
            mockMvc.perform(admin.apply(post(url)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms." + table + " WHERE organization_id = ?").param(org)
                .query(Integer.class).single())).isZero();
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void listIsScopedToTheCallersOrganizationFilteredAndPaged(String url, String entity, String table,
            String duplicateCode) throws Exception {
        create(admin, url, "ALPHA", "Alpha");
        String beta = create(admin, url, "BETA", "Beta");
        create(admin, url, "GAMMA", "Gamma");
        mockMvc.perform(admin.apply(patch(url + "/" + JsonPath.read(beta, "$.id")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"active\":false}"))
                .andExpect(status().isOk());
        UUID other = fx.organization();
        create(fx.loggedIn(other, "facility", "FACILITY_ADMIN"), url, "OTHER", "Other Organization Entry");

        mockMvc.perform(admin.apply(get(url)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.totalItems", is(3)))
                .andExpect(jsonPath("$.items[0].code", is("ALPHA")))
                .andExpect(jsonPath("$.items[2].code", is("GAMMA")));
        mockMvc.perform(admin.apply(get(url + "?active=true")))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalItems", is(2)));
        mockMvc.perform(admin.apply(get(url + "?active=false")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].code", is("BETA")));
        mockMvc.perform(admin.apply(get(url + "?page=1&size=2")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.totalItems", is(3)));
        for (String bad : List.of("?size=0", "?size=101", "?page=-1")) {
            mockMvc.perform(admin.apply(get(url + bad))).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void patchRenamesDeactivatesAndReactivatesWithAudit(String url, String entity, String table,
            String duplicateCode) throws Exception {
        String created = create(admin, url, "CARDIO", "Cardiology");
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Cardiac Care\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Cardiac Care")))
                .andExpect(jsonPath("$.code", is("CARDIO")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)))
                .andExpect(jsonPath("$.version", is(2)));
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":2,\"active\":true,\"name\":\"Cardiology\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.version", is(3)));
        // Nothing to change: no version bump, no audit event.
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":3,\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(3)));

        assertThat(fx.auditActions(UUID.fromString(id))).containsExactly(entity + "_CREATED", entity + "_UPDATED",
                entity + "_DEACTIVATED", entity + "_ACTIVATED");
        List<String> fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? "
                        + "AND action <> '" + entity + "_CREATED'").param(UUID.fromString(id))
                .query(String.class).list();
        assertThat(String.join(" ", fields)).contains("name", "active").doesNotContain("Cardiac", "Cardiology");
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void invalidPatchIsRejectedAndNothingChanges(String url, String entity, String table, String duplicateCode)
            throws Exception {
        String id = JsonPath.read(create(admin, url, "CARDIO", "Cardiology"), "$.id");
        for (String body : List.of(
                "{\"version\":0}",
                "{\"name\":\"Missing Version\"}",
                "{\"version\":0,\"name\":\"   \"}",
                "{\"version\":0,\"name\":\"" + "x".repeat(101) + "\"}")) {
            mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        // The code is immutable: it is not a patchable field and a supplied one is ignored.
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"code\":\"OTHER\",\"name\":\"Cardiology Unit\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code", is("CARDIO")));
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void staleVersionIsAConflict(String url, String entity, String table, String duplicateCode) throws Exception {
        String id = JsonPath.read(create(admin, url, "CARDIO", "Cardiology"), "$.id");
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"First Change\"}")).andExpect(status().isOk());

        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Second Change\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(admin.apply(patch(url + "/" + id)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"active\":false}"))
                .andExpect(status().isConflict());
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT name FROM hms." + table + " WHERE id = ?").param(UUID.fromString(id))
                .query(String.class).single())).isEqualTo("First Change");
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void anotherOrganizationsIdIsNotFoundAndUntouched(String url, String entity, String table,
            String duplicateCode) throws Exception {
        UUID other = fx.organization();
        String foreign = JsonPath.read(create(fx.loggedIn(other, "facility", "FACILITY_ADMIN"), url, "CARDIO",
                "Foreign Entry"), "$.id");
        mockMvc.perform(admin.apply(patch(url + "/" + foreign)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mockMvc.perform(admin.apply(patch(url + "/" + UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Nobody\"}")).andExpect(status().isNotFound());
        assertThat(fx.inOrg(other, () -> jdbc.sql("SELECT name FROM hms." + table + " WHERE id = ?").param(UUID.fromString(foreign))
                .query(String.class).single())).isEqualTo("Foreign Entry");
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void thereIsNoDeleteEndpointAndTheDatabaseRefusesDelete(String url, String entity, String table,
            String duplicateCode) throws Exception {
        String id = JsonPath.read(create(admin, url, "CARDIO", "Cardiology"), "$.id");
        mockMvc.perform(admin.apply(delete(url + "/" + id))).andExpect(status().isMethodNotAllowed());

        // The runtime role (hms_app) has no DELETE grant at all: retiring an entry is active=false.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms." + table + " WHERE id = ?")
                .param(UUID.fromString(id)).update()))
                .rootCause().hasMessageContaining("permission denied");
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms." + table + " WHERE id = ?").param(UUID.fromString(id))
                .query(Integer.class).single())).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(textBlock = KINDS)
    void rlsHidesAnotherOrganizationsRowsEvenWithoutAnOrgFilter(String url, String entity, String table,
            String duplicateCode) throws Exception {
        create(admin, url, "CARDIO", "Cardiology");
        UUID other = fx.organization();
        create(fx.loggedIn(other, "facility", "FACILITY_ADMIN"), url, "CARDIO", "Foreign Entry");

        List<UUID> seen = fx.inOrg(org, () -> jdbc.sql("SELECT DISTINCT organization_id FROM hms." + table)
                .query(UUID.class).list());
        assertThat(seen).containsExactly(org);
        assertThat(fx.inOrg(org, () -> jdbc.sql("UPDATE hms." + table + " SET name = 'x' WHERE organization_id = ?")
                .param(other).update())).isZero();
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("INSERT INTO hms." + table
                        + " (id, organization_id, code, name, created_at, updated_at)"
                        + " VALUES (?, ?, 'SMUGGLED', 'Smuggled', now(), now())")
                .params(UUID.randomUUID(), other).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        // No organization context at all: fail closed.
        Integer noContext = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(s -> jdbc.sql("SELECT count(*) FROM hms." + table).query(Integer.class).single());
        assertThat(noContext).isZero();
    }
}
