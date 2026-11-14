package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * [E3-7] Patient reads are audited fail-closed. The failure is injected in the database: a trigger created as the
 * owner makes every PATIENT_VIEWED insert into hms.audit_event raise, so the real service, recorder and
 * repository run unchanged. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatientReadAuditIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;
    private static final String NAME = "Test Auditee Reader";

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
    private AdminApiFixture.Session reception;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        reception = fx.loggedIn(org, "reception", "RECEPTIONIST");
    }

    @AfterEach
    void removeFault() throws Exception {
        ownerSql("DROP TRIGGER IF EXISTS fail_view_audit ON hms.audit_event");
    }

    private void ownerSql(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER, OWNER_PASSWORD);
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private void injectViewAuditFault() throws Exception {
        ownerSql("""
                CREATE OR REPLACE FUNCTION hms.test_fail_view_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.action = 'PATIENT_VIEWED' THEN
                        RAISE EXCEPTION 'injected audit failure';
                    END IF;
                    RETURN NEW;
                END $$""");
        ownerSql("CREATE TRIGGER fail_view_audit BEFORE INSERT ON hms.audit_event "
                + "FOR EACH ROW EXECUTE FUNCTION hms.test_fail_view_audit()");
    }

    private UUID registered() throws Exception {
        String response = mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(
                "{\"fullName\":\"" + NAME + "\",\"sex\":\"F\",\"dateOfBirth\":\"1985-03-02\","
                        + "\"phone\":\"+910400000001\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private long views(UUID patient) {
        return jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE entity_id = ? AND action = 'PATIENT_VIEWED'")
                .param(patient).query(Long.class).single();
    }

    @Test
    void detailReadWritesOneViewEventWithoutPhi() throws Exception {
        UUID patient = registered();
        mockMvc.perform(reception.apply(get(URL + "/" + patient))).andExpect(status().isOk());
        assertThat(views(patient)).isEqualTo(1);
        String row = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ? "
                + "AND action = 'PATIENT_VIEWED'").param(patient).query(String.class).single();
        assertThat(row).doesNotContain(NAME).doesNotContain("+910400000001").doesNotContain("1985-03-02");
    }

    @Test
    void failedAuditWriteFailsTheReadAndReturnsNoPatientData() throws Exception {
        UUID patient = registered();
        injectViewAuditFault();

        mockMvc.perform(reception.apply(get(URL + "/" + patient)))
                .andExpect(status().is5xxServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(content().string(not(containsString(NAME))))
                .andExpect(content().string(not(containsString("injected"))));
        assertThat(views(patient)).isZero();
    }

    @Test
    void noOpUpdateReturnsThePatientSoItIsAuditedAndFailsClosedToo() throws Exception {
        UUID patient = registered();
        String noOp = "{\"version\":0,\"phone\":\"+910400000001\"}";
        mockMvc.perform(reception.apply(patch(URL + "/" + patient)).contentType(JSON).content(noOp))
                .andExpect(status().isOk());
        assertThat(views(patient)).isEqualTo(1);

        injectViewAuditFault();
        mockMvc.perform(reception.apply(patch(URL + "/" + patient)).contentType(JSON).content(noOp))
                .andExpect(status().is5xxServerError())
                .andExpect(content().string(not(containsString(NAME))));
        assertThat(views(patient)).isEqualTo(1);
    }

    @Test
    void otherOrganizationsPatientIs404AndWritesNoReadAudit() throws Exception {
        UUID patient = registered();
        UUID other = fx.organization();
        AdminApiFixture.Session outsider = fx.loggedIn(other, "reception", "RECEPTIONIST");
        mockMvc.perform(outsider.apply(get(URL + "/" + patient))).andExpect(status().isNotFound());
        mockMvc.perform(outsider.apply(get(URL + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(views(patient)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE organization_id = ? "
                + "AND action = 'PATIENT_VIEWED'").param(other).query(Long.class).single()).isZero();
    }

    @Test
    void allergyAndHistoryListsWriteOnlyTheirOwnEventNotAPatientView() throws Exception {
        UUID patient = registered();
        mockMvc.perform(reception.apply(get(URL + "/" + patient + "/allergies"))).andExpect(status().isOk());
        mockMvc.perform(reception.apply(get(URL + "/" + patient + "/medical-history"))).andExpect(status().isOk());
        assertThat(fx.auditActions(patient)).containsExactly("PATIENT_CREATED", "PATIENT_ALLERGIES_VIEWED",
                "PATIENT_HISTORY_VIEWED");
    }
}
