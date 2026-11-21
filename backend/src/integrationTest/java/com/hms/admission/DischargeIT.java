package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * [E8-7] Discharge summary and the atomic discharge transaction (rulings R6, R7). The stay is back-dated on a direct
 * hms_owner connection so the planned discharge can be in the past. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DischargeIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

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
    private AdminApiFixture.Session clerk;
    private IpdFixture ipd;
    private UUID ward;
    private UUID doctor;
    private int beds;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("DIS");
        doctor = ipd.doctor();
        owner("INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, created_by,"
                + " created_at) VALUES ('" + UUID.randomUUID() + "', '" + org + "', '" + ipd.rateService()
                + "', 1200.00, '2020-01-01T00:00:00Z', '" + clerk.userId() + "', now())");
    }

    private void owner(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER, OWNER_PASSWORD)) {
            try (PreparedStatement ctx = c.prepareStatement("SELECT set_config('app.organization_id', ?, false)")) {
                ctx.setString(1, org.toString());
                ctx.execute();
            }
            try (PreparedStatement st = c.prepareStatement(sql)) {
                st.executeUpdate();
            }
        }
    }

    /** The SQLSTATE of a statement run as hms_owner in the organization's context, or null when it succeeds. */
    private String sqlState(String sql) {
        try {
            owner(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    /** An admission admitted three days ago in a fresh bed. */
    private UUID[] admittedDaysAgo() throws Exception {
        UUID bed = ipd.bed(ward, "D-" + ++beds);
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, bed);
        Instant at = Instant.now().minus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        owner("UPDATE hms.admission SET admitted_at = '" + at + "' WHERE id = '" + id + "'");
        owner("UPDATE hms.bed_assignment SET assigned_at = '" + at + "' WHERE admission_id = '" + id + "'");
        return new UUID[] {id, bed};
    }

    private static Instant planned() {
        return Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String summaryBody(Long version, Instant planned) {
        return "{" + (version == null ? "" : "\"version\":" + version + ",")
                + "\"finalDiagnosis\":\"Synthetic final diagnosis\",\"courseInHospital\":\"Synthetic course\","
                + "\"procedures\":\"None\",\"conditionAtDischarge\":\"Stable\",\"followUp\":\"Review in 7 days\","
                + "\"medications\":[{\"name\":\"Synthetic tab A\",\"dose\":\"500 mg\",\"frequency\":\"1-0-1\","
                + "\"duration\":\"5 days\"},{\"name\":\"Synthetic syrup B\",\"dose\":\"10 ml\",\"frequency\":\"0-0-1\","
                + "\"duration\":\"3 days\"}],\"plannedDischargeAt\":\"" + planned + "\"}";
    }

    private ResultActions putSummary(AdminApiFixture.Session who, UUID id, String body) throws Exception {
        return mockMvc.perform(who.apply(put("/api/v1/admissions/" + id + "/discharge-summary")).contentType(JSON)
                .content(body));
    }

    private long admissionVersion(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT version FROM hms.admission WHERE id = ?").param(id)
                .query(Long.class).single());
    }

    private String admissionStatus(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.admission WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private ResultActions discharge(AdminApiFixture.Session who, UUID id, long version, Instant at, String invoiceId,
            boolean defer) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/admissions/" + id + "/discharge")).contentType(JSON)
                .content("{\"version\":" + version + ",\"dischargedAt\":\"" + at + "\""
                        + (invoiceId == null ? "" : ",\"invoiceId\":\"" + invoiceId + "\"")
                        + ",\"deferBilling\":" + defer + "}"));
    }

    private String finalInvoice(UUID id) throws Exception {
        UUID patient = fx.inOrg(org, () -> jdbc.sql("SELECT patient_id FROM hms.admission WHERE id = ?").param(id)
                .query(UUID.class).single());
        return mockMvc.perform(clerk.apply(post("/api/v1/invoices")).contentType(JSON)
                        .content("{\"type\":\"IPD\",\"patientId\":\"" + patient + "\",\"admissionId\":\"" + id
                                + "\",\"kind\":\"FINAL\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private void issue(String invoice) throws Exception {
        mockMvc.perform(clerk.apply(post("/api/v1/invoices/" + JsonPath.read(invoice, "$.id") + "/issue"))
                .header("Idempotency-Key", "key-" + UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    void happyPathReleasesBedAndFreezesSummary() throws Exception {
        UUID[] a = admittedDaysAgo();
        UUID id = a[0];
        Instant planned = planned();
        putSummary(clerk, id, summaryBody(null, planned)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(0))).andExpect(jsonPath("$.medications.length()", is(2)))
                .andExpect(jsonPath("$.medications[1].name", is("Synthetic syrup B")))
                .andExpect(jsonPath("$.plannedDischargeAt", is(planned.toString())));
        assertThat(admissionStatus(id)).isEqualTo("DISCHARGE_INITIATED");
        mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + id + "/discharge-summary")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conditionAtDischarge", is("Stable")))
                .andExpect(jsonPath("$.plannedDischargeAt", is(planned.toString())));
        // A second save needs the summary version.
        putSummary(clerk, id, summaryBody(0L, planned)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(1)));
        putSummary(clerk, id, summaryBody(0L, planned)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));

        String invoice = finalInvoice(id);
        issue(invoice);
        // Changing the planned time once a FINAL exists is refused.
        putSummary(clerk, id, summaryBody(1L, planned.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));

        discharge(clerk, id, admissionVersion(id), planned, JsonPath.read(invoice, "$.id"), false)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DISCHARGED")))
                .andExpect(jsonPath("$.bedReleased", is(true)));
        assertThat(admissionStatus(id)).isEqualTo("DISCHARGED");
        assertThat(ipd.openAssignments(a[1])).isZero();
        mockMvc.perform(clerk.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(status().isOk());
        String released = fx.inOrg(org, () -> jdbc.sql(
                "SELECT released_at FROM hms.bed_assignment WHERE admission_id = ?").param(id)
                .query((rs, n) -> rs.getTimestamp(1).toInstant().toString()).single());
        assertThat(released).isEqualTo(planned.toString());
        assertThat(fx.auditActions(id)).contains("DISCHARGE_SUMMARY_SAVED", "PATIENT_DISCHARGED", "BED_RELEASED");

        // Immutable after discharge: the service refuses, and so does the database guard (SQLSTATE HM006).
        putSummary(clerk, id, summaryBody(1L, planned)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(sqlState("UPDATE hms.discharge_summary SET follow_up = 'x' WHERE admission_id = '" + id + "'"))
                .isEqualTo("HM006");
        assertThat(sqlState("DELETE FROM hms.discharge_medication WHERE organization_id = '" + org + "'"))
                .isEqualTo("HM006");
        assertThat(sqlState("DELETE FROM hms.discharge_summary WHERE admission_id = '" + id + "'"))
                .isEqualTo("HM006");
        // A new admission may now take the bed.
        ipd.admitted(clerk, ipd.patient(), doctor, a[1]);
    }

    @Test
    void summaryNeedsRequiredFieldsAndAnActiveAdmission() throws Exception {
        UUID id = admittedDaysAgo()[0];
        putSummary(clerk, id, "{\"courseInHospital\":\"x\",\"plannedDischargeAt\":\"" + planned() + "\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        putSummary(clerk, id, "{\"finalDiagnosis\":\"d\",\"conditionAtDischarge\":\"c\"}")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        putSummary(clerk, id, summaryBody(null, Instant.now().minus(10, ChronoUnit.DAYS)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + id + "/discharge-summary")))
                .andExpect(status().isNotFound());
        assertThat(admissionStatus(id)).isEqualTo("ADMITTED");
    }

    @Test
    void plannedDischargeMustNotPrecedeTheCurrentBedAssignment() throws Exception {
        UUID id = admittedDaysAgo()[0];
        Instant assigned = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        owner("UPDATE hms.bed_assignment SET assigned_at = '" + assigned + "' WHERE admission_id = '" + id + "'");
        putSummary(clerk, id, summaryBody(null, assigned.minus(1, ChronoUnit.HOURS)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        putSummary(clerk, id, summaryBody(null, assigned.plus(1, ChronoUnit.HOURS))).andExpect(status().isOk());
    }

    @Test
    void dischargeBillingRules() throws Exception {
        UUID id = admittedDaysAgo()[0];
        Instant planned = planned();
        putSummary(clerk, id, summaryBody(null, planned)).andExpect(status().isOk());
        long v = admissionVersion(id);

        // No invoice and no defer.
        discharge(clerk, id, v, planned, null, false).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
        // Defer without discharge:defer-billing.
        fx.role(org, "DISCHARGER", Permissions.DISCHARGE_COMPLETE);
        AdminApiFixture.Session nurse = fx.loggedIn(org, "discharger", "DISCHARGER");
        discharge(nurse, id, v, planned, null, true).andExpect(status().isForbidden());
        // DRAFT final invoice.
        String invoice = finalInvoice(id);
        String invoiceId = JsonPath.read(invoice, "$.id");
        discharge(clerk, id, v, planned, invoiceId, false).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
        issue(invoice);
        // dischargedAt other than the planned discharge (the FINAL's charge end).
        discharge(clerk, id, v, planned.minus(1, ChronoUnit.MINUTES), invoiceId, false)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
        // Future dischargedAt.
        discharge(clerk, id, v, Instant.now().plus(1, ChronoUnit.HOURS), null, true)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // Stale version.
        discharge(clerk, id, v - 1, planned, invoiceId, false).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        assertThat(admissionStatus(id)).isEqualTo("DISCHARGE_INITIATED");
        assertThat(fx.auditActions(id)).doesNotContain("PATIENT_DISCHARGED");
    }

    @Test
    void deferredBillingWithPermission() throws Exception {
        UUID[] a = admittedDaysAgo();
        Instant planned = planned();
        putSummary(clerk, a[0], summaryBody(null, planned)).andExpect(status().isOk());
        discharge(clerk, a[0], admissionVersion(a[0]), planned.plus(10, ChronoUnit.MINUTES), null, true)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("DISCHARGED")));
        assertThat(ipd.openAssignments(a[1])).isZero();
    }

    @Test
    void dischargeNeedsDischargeInitiated() throws Exception {
        UUID id = admittedDaysAgo()[0];
        discharge(clerk, id, admissionVersion(id), planned(), null, true).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void otherOrganizationIs404() throws Exception {
        UUID id = admittedDaysAgo()[0];
        UUID other = fx.organization();
        AdminApiFixture.Session stranger = fx.withAllPermissions(other);
        putSummary(stranger, id, summaryBody(null, planned())).andExpect(status().isNotFound());
        mockMvc.perform(stranger.apply(get("/api/v1/admissions/" + id + "/discharge-summary")))
                .andExpect(status().isNotFound());
        discharge(stranger, id, 0, planned(), null, true).andExpect(status().isNotFound());
        assertThat(admissionStatus(id)).isEqualTo("ADMITTED");
    }
}
