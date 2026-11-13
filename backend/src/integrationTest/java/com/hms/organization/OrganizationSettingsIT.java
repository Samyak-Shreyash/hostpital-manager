package com.hms.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E2-1] Organization settings (api-design.md section 4). Synthetic data only. Negative tests use a valid CSRF
 * header and a real session, so a 401/403 can only come from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrganizationSettingsIT extends PostgresIT {

    private static final String URL = "/api/v1/organization";
    private static final String VALID_GSTIN = "27AAPFU0939F1ZV";

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
    void endpointsNeedASession() throws Exception {
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(URL).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"name\":\"X\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readNeedsOrgReadAndUpdateNeedsOrgUpdate() throws Exception {
        fx.role(org, "READ_ONLY", "org:read");
        fx.role(org, "UPDATE_ONLY", "org:update");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READ_ONLY");
        AdminApiFixture.Session updater = fx.loggedIn(org, "updater", "UPDATE_ONLY");
        String body = "{\"version\":0,\"name\":\"Never Renamed\"}";

        mockMvc.perform(reader.apply(get(URL))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(updater.apply(get(URL))).andExpect(status().isForbidden());
        mockMvc.perform(updater.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void getReturnsTheCallersOwnOrganization() throws Exception {
        UUID other = fx.organization();
        jdbc.sql("UPDATE hms.organization SET name = 'Other Clinic' WHERE id = ?").param(other).update();

        mockMvc.perform(admin.apply(get(URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(org.toString())))
                .andExpect(jsonPath("$.timezone", is("Asia/Kolkata")))
                .andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void updateChangesFieldsBumpsVersionAndAuditsFieldNamesOnly() throws Exception {
        String body = "{\"version\":0,\"name\":\"Demo Nursing Home\",\"timezone\":\"Asia/Dubai\","
                + "\"gstin\":\"" + VALID_GSTIN + "\",\"address\":\"12 Example Road, Sampletown\"}";
        mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Demo Nursing Home")))
                .andExpect(jsonPath("$.timezone", is("Asia/Dubai")))
                .andExpect(jsonPath("$.gstin", is(VALID_GSTIN)))
                .andExpect(jsonPath("$.address", is("12 Example Road, Sampletown")))
                .andExpect(jsonPath("$.version", is(1)));

        assertThat(fx.auditActions(org)).contains("ORGANIZATION_UPDATED");
        List<String> fields = jdbc.sql("""
                SELECT changed_fields::text FROM hms.audit_event
                WHERE entity_id = ? AND action = 'ORGANIZATION_UPDATED'
                """).param(org).query(String.class).list();
        assertThat(fields).hasSize(1);
        assertThat(fields.get(0)).contains("name", "timezone", "gstin", "address")
                .doesNotContain("Demo Nursing Home", VALID_GSTIN, "Example Road");
    }

    @Test
    void emptyGstinAndAddressClearThem() throws Exception {
        mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"gstin\":\"" + VALID_GSTIN + "\",\"address\":\"1 Example Lane\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"gstin\":\"\",\"address\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gstin").doesNotExist())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.version", is(2)));
    }

    @Test
    void invalidInputIsRejectedAndNothingChanges() throws Exception {
        for (String body : List.of(
                "{\"version\":0,\"timezone\":\"Mars/Olympus_Mons\"}",
                "{\"version\":0,\"timezone\":\"+05:30\"}",
                "{\"version\":0,\"gstin\":\"NOT-A-GSTIN\"}",
                "{\"version\":0,\"gstin\":\"27aapfu0939f1zv\"}",
                "{\"version\":0,\"name\":\"   \"}",
                "{\"version\":0}",
                "{\"name\":\"Missing Version\"}")) {
            mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        mockMvc.perform(admin.apply(get(URL))).andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void staleVersionIsAConflict() throws Exception {
        mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"First Change\"}")).andExpect(status().isOk());

        ResultActions stale = mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Second Change\"}"));
        stale.andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));

        String name = JsonPath.read(mockMvc.perform(admin.apply(get(URL))).andReturn().getResponse()
                .getContentAsString(), "$.name");
        assertThat(name).isEqualTo("First Change");
    }

    @Test
    void anotherOrganizationIsNeverTouched() throws Exception {
        UUID other = fx.organization();
        String before = jdbc.sql("SELECT name FROM hms.organization WHERE id = ?").param(other)
                .query(String.class).single();
        mockMvc.perform(admin.apply(patch(URL)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"name\":\"Renamed Own Clinic\"}")).andExpect(status().isOk());
        String after = jdbc.sql("SELECT name FROM hms.organization WHERE id = ?").param(other)
                .query(String.class).single();
        assertThat(after).isEqualTo(before);
    }

    @Test
    void loginResponseCarriesOrganizationNameAndTimezone() throws Exception {
        mockMvc.perform(admin.apply(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.id", is(org.toString())))
                .andExpect(jsonPath("$.organization.timezone", is("Asia/Kolkata")))
                .andExpect(jsonPath("$.organization.name").isNotEmpty());
    }
}
