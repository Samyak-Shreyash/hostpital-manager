package com.hms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** [E1-9] Audit viewer: permission, filters, pagination, organization scoping and the audited read. Synthetic data. */
@SpringBootTest
@AutoConfigureMockMvc
class AuditViewerIT extends PostgresIT {

    private static final String URL = "/api/v1/audit-events";

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
        admin = fx.loggedIn(org, "sysadmin", "SYSTEM_ADMIN");
    }

    private void seed(UUID organization, UUID actor, String action, String entityType, UUID entityId, Instant at,
            String... fields) {
        jdbc.sql("""
                INSERT INTO hms.audit_event (id, organization_id, occurred_at, actor_id, action, entity_type,
                    entity_id, outcome, correlation_id, changed_fields)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'SUCCESS', 'corr-demo-1', ?)
                """).params(UUID.randomUUID(), organization, Timestamp.from(at), actor, action, entityType, entityId,
                fields).update();
    }

    private long viewedCount(UUID organization) {
        return jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE organization_id = ? AND action = 'AUDIT_VIEWED'")
                .param(organization).query(Long.class).single();
    }

    @Test
    void needsASessionAndAuditRead() throws Exception {
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        fx.role(org, "NO_AUDIT", "patient:read");
        AdminApiFixture.Session nobody = fx.loggedIn(org, "noaudit", "NO_AUDIT");
        mockMvc.perform(nobody.apply(get(URL))).andExpect(status().isForbidden());
        assertThat(viewedCount(org)).isZero();
        mockMvc.perform(admin.apply(get(URL))).andExpect(status().isOk());
    }

    @Test
    void filtersCombineAndNewestComesFirst() throws Exception {
        UUID actorA = fx.user(org, fx.uniqueEmail("actor-a"));
        UUID actorB = fx.user(org, fx.uniqueEmail("actor-b"));
        UUID entity = UUID.randomUUID();
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        seed(org, actorA, "DEMO_CREATED", "DEMO_THING", entity, base.minusSeconds(30), "address.city", "name");
        seed(org, actorB, "DEMO_UPDATED", "DEMO_THING", entity, base.minusSeconds(20), "name");
        seed(org, actorA, "DEMO_VIEWED", "DEMO_OTHER", UUID.randomUUID(), base.minusSeconds(10));

        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_THING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.items[0].action", is("DEMO_UPDATED")))
                .andExpect(jsonPath("$.items[1].action", is("DEMO_CREATED")))
                .andExpect(jsonPath("$.items[1].changedFields", is(List.of("address.city", "name"))))
                .andExpect(jsonPath("$.items[1].correlationId", is("corr-demo-1")))
                .andExpect(jsonPath("$.items[1].actorId", is(actorA.toString())))
                .andExpect(jsonPath("$.items[1].entityId", is(entity.toString())));
        mockMvc.perform(admin.apply(get(URL).param("actorId", actorA.toString())))
                .andExpect(jsonPath("$.totalItems", is(2)));
        mockMvc.perform(admin.apply(get(URL).param("action", "DEMO_UPDATED")))
                .andExpect(jsonPath("$.items", hasSize(1)));
        mockMvc.perform(admin.apply(get(URL).param("entityId", entity.toString())))
                .andExpect(jsonPath("$.totalItems", is(2)));
        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_THING").param("actorId", actorB.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
        // The range is inclusive on both ends.
        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_THING")
                        .param("from", base.minusSeconds(25).toString()).param("to", base.minusSeconds(20).toString())))
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.items[0].action", is("DEMO_UPDATED")));
    }

    @Test
    void paginates() throws Exception {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < 5; i++) {
            seed(org, null, "DEMO_PAGED", "DEMO_PAGE", null, base.minusSeconds(i));
        }
        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_PAGE").param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page", is(1)))
                .andExpect(jsonPath("$.size", is(2)))
                .andExpect(jsonPath("$.totalItems", is(5)))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_PAGE").param("page", "2").param("size", "2")))
                .andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void neverShowsAnotherOrganizationsEvents() throws Exception {
        UUID orgB = fx.organization();
        UUID entity = UUID.randomUUID();
        Instant now = Instant.now();
        seed(orgB, null, "DEMO_SECRET", "DEMO_THING", entity, now.minusSeconds(5));
        seed(org, null, "DEMO_OWN", "DEMO_THING", entity, now.minusSeconds(4));

        mockMvc.perform(admin.apply(get(URL).param("entityType", "DEMO_THING")))
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.items[0].action", is("DEMO_OWN")));
        mockMvc.perform(admin.apply(get(URL).param("action", "DEMO_SECRET")))
                .andExpect(jsonPath("$.totalItems", is(0)));
        // The organization cannot be chosen by request.
        mockMvc.perform(admin.apply(get(URL).param("organizationId", orgB.toString()).param("action", "DEMO_SECRET")))
                .andExpect(jsonPath("$.totalItems", is(0)));
        // Reading org A's log wrote events for org A only.
        assertThat(viewedCount(orgB)).isZero();
        assertThat(viewedCount(org)).isEqualTo(3);
    }

    @Test
    void everyQueryIsAuditedWithFilterNamesOnly() throws Exception {
        UUID entity = UUID.randomUUID();
        mockMvc.perform(admin.apply(get(URL).param("action", "DEMO_VIEWED").param("entityId", entity.toString())
                        .param("from", "2020-01-01T00:00:00Z")))
                .andExpect(status().isOk());
        assertThat(viewedCount(org)).isEqualTo(1);
        Map<String, Object> row = jdbc.sql("""
                SELECT actor_id, entity_type, entity_id, outcome, changed_fields::text AS fields
                FROM hms.audit_event WHERE organization_id = ? AND action = 'AUDIT_VIEWED'
                """).param(org).query().singleRow();
        assertThat(row.get("actor_id")).isEqualTo(admin.userId());
        assertThat(row.get("entity_type")).isEqualTo("AUDIT_EVENT");
        assertThat(row.get("entity_id")).isNull();
        assertThat(row.get("outcome")).isEqualTo("SUCCESS");
        assertThat((String) row.get("fields")).isEqualTo("{action,entityId,from}")
                .doesNotContain("DEMO_VIEWED").doesNotContain(entity.toString());
        // The event is visible to the next query.
        mockMvc.perform(admin.apply(get(URL).param("action", "AUDIT_VIEWED")))
                .andExpect(jsonPath("$.totalItems", is(1)));
    }

    @Test
    void invalidInputIs422WithoutAnAuditEvent() throws Exception {
        String[][] bad = {{"actorId", "not-a-uuid"}, {"entityId", "123"}, {"action", "lower_case"},
                {"entityType", "X"}, {"from", "yesterday"}, {"to", "2026-01-01"}, {"page", "-1"}, {"size", "0"},
                {"size", "101"}, {"page", "abc"}, {"size", "ten"}};
        for (String[] param : bad) {
            mockMvc.perform(admin.apply(get(URL).param(param[0], param[1])))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        mockMvc.perform(admin.apply(get(URL).param("from", "2026-02-01T00:00:00Z").param("to", "2026-01-01T00:00:00Z")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        assertThat(viewedCount(org)).isZero();
    }

    @Test
    void facilityAdministratorCanReadTheAuditLog() throws Exception {
        AdminApiFixture.Session facility = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        mockMvc.perform(facility.apply(get(URL))).andExpect(status().isOk());
        assertThat(viewedCount(org)).isEqualTo(1);
    }

    /** V11 backfill: re-runs its statement against a role created before the template change (idempotent). */
    @Test
    void migrationBackfillsExistingFacilityAdministratorRoles() throws Exception {
        UUID facilityRole = fx.roleId(org, "FACILITY_ADMIN");
        fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.role_permission WHERE role_id = ? AND permission_code = 'audit:read'")
                .param(facilityRole).update());
        AdminApiFixture.Session facility = fx.loggedIn(org, "facility2", "FACILITY_ADMIN");
        mockMvc.perform(facility.apply(get(URL))).andExpect(status().isForbidden());

        String migration = new String(getClass().getResourceAsStream("/db/migration/V11__facility_admin_audit_read.sql")
                .readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        for (int run = 0; run < 2; run++) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> jdbc.sql(migration).update());
        }
        AdminApiFixture.Session fresh = fx.login(facility.userId(), facility.email());
        mockMvc.perform(fresh.apply(get(URL))).andExpect(status().isOk());
    }
}
