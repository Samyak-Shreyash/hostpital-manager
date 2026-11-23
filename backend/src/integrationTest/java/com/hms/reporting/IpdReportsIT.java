package com.hms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import org.springframework.transaction.PlatformTransactionManager;

/** [E9-2] IPD reports: active admissions, bed occupancy, discharges. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class IpdReportsIT extends PostgresIT {

    private static final String RANGE = "?from=2026-11-01&to=2026-11-03";

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
    private UUID doctor;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        doctor = ipd.doctor();
        seedOtherOrganization();
    }

    @Test
    void occupancyCountsOpenAssignmentsAndIgnoresReleasedOnes() throws Exception {
        UUID ward1 = ipd.ward("OCC1");
        UUID a = ipd.bed(ward1, "A");
        ipd.bed(ward1, "B");
        UUID c = ipd.bed(ward1, "C");
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.bed SET status = 'OUT_OF_SERVICE' WHERE id = ?").param(c).update());
        UUID ward2 = ipd.ward("OCC2");
        UUID d = ipd.bed(ward2, "D");
        UUID admission = ipd.admitted(clerk, ipd.patient(), doctor, a);

        mockMvc.perform(clerk.apply(get("/api/v1/reports/beds/occupancy")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report", is("beds-occupancy")))
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.columns[0]", is("wardName")))
                .andExpect(jsonPath("$.rows.length()", is(3)))
                .andExpect(jsonPath("$.rows[0].wardName", is("Ward OCC1")))
                .andExpect(jsonPath("$.rows[0].totalBeds", is(3)))
                .andExpect(jsonPath("$.rows[0].occupied", is(1)))
                .andExpect(jsonPath("$.rows[0].outOfService", is(1)))
                .andExpect(jsonPath("$.rows[0].available", is(1)))
                .andExpect(jsonPath("$.rows[0].occupancyPercent", is("50.0")))
                .andExpect(jsonPath("$.rows[1].wardName", is("Ward OCC2")))
                .andExpect(jsonPath("$.rows[1].occupied", is(0)))
                .andExpect(jsonPath("$.rows[1].occupancyPercent", is("0.0")))
                .andExpect(jsonPath("$.rows[2].wardName", is("Total")))
                .andExpect(jsonPath("$.rows[2].totalBeds", is(4)));

        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + admission + "/bed-assignments/transfer"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toBedId\":\"" + d + "\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(clerk.apply(get("/api/v1/reports/beds/occupancy")))
                .andExpect(jsonPath("$.rows[0].occupied", is(0)))
                .andExpect(jsonPath("$.rows[0].available", is(2)))
                .andExpect(jsonPath("$.rows[0].occupancyPercent", is("0.0")))
                .andExpect(jsonPath("$.rows[1].occupied", is(1)))
                .andExpect(jsonPath("$.rows[1].occupancyPercent", is("100.0")))
                .andExpect(jsonPath("$.rows[2].occupied", is(1)))
                .andExpect(jsonPath("$.rows[2].outOfService", is(1)))
                .andExpect(jsonPath("$.rows[2].occupancyPercent", is("33.3")));
    }

    @Test
    void occupiedSumsToTheOpenBedAssignments() throws Exception {
        UUID ward = ipd.ward("SUMW");
        for (String bed : new String[] {"S1", "S2", "S3"}) {
            ipd.admitted(clerk, ipd.patient(), doctor, ipd.bed(ward, bed));
        }
        long open = fx.inOrg(org, () -> jdbc.sql(
                "SELECT count(*) FROM hms.bed_assignment WHERE organization_id = ? AND released_at IS NULL")
                .param(org).query(Long.class).single());
        String body = mockMvc.perform(clerk.apply(get("/api/v1/reports/beds/occupancy")))
                .andReturn().getResponse().getContentAsString();
        int sum = com.jayway.jsonpath.JsonPath.<java.util.List<Integer>>read(body,
                "$.rows[?(@.wardName != 'Total')].occupied").stream().mapToInt(Integer::intValue).sum();
        assertThat(sum).isEqualTo(open).isEqualTo(3);
    }

    @Test
    void activeAdmissionsListTheCurrentBed() throws Exception {
        UUID ward = ipd.ward("ACT1");
        UUID a = ipd.bed(ward, "A1");
        UUID b = ipd.bed(ward, "A2");
        UUID admission = ipd.admitted(clerk, ipd.patient(), doctor, a);
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + admission + "/bed-assignments/transfer"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"toBedId\":\"" + b + "\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(clerk.apply(get("/api/v1/reports/admissions/active")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()", is(1)))
                .andExpect(jsonPath("$.rows[0].uhid", is("HMS-000501")))
                .andExpect(jsonPath("$.rows[0].patientName", is("Test Inpatient")))
                .andExpect(jsonPath("$.rows[0].wardName", is("Ward ACT1")))
                .andExpect(jsonPath("$.rows[0].bedNo", is("A2")))
                .andExpect(jsonPath("$.rows[0].doctorName", is("Dr. Test Ward")))
                .andExpect(jsonPath("$.rows[0].status", is("ADMITTED")));
    }

    @Test
    void dischargesAreBucketedByTheOrganizationLocalDate() throws Exception {
        discharged(org, ipd.patient(), doctor, "2026-11-01T18:31:00Z"); // 00:01 on 2 Nov in Asia/Kolkata
        discharged(org, ipd.patient(), doctor, "2026-11-01T18:29:00Z"); // 23:59 on 1 Nov

        mockMvc.perform(clerk.apply(get("/api/v1/reports/discharges" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()", is(3)))
                .andExpect(jsonPath("$.rows[0].date", is("2026-11-01")))
                .andExpect(jsonPath("$.rows[0].count", is(1)))
                .andExpect(jsonPath("$.rows[1].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[1].count", is(1)))
                .andExpect(jsonPath("$.rows[2].count", is(0)));
        mockMvc.perform(clerk.apply(get("/api/v1/reports/discharges?from=2026-11-03&to=2026-11-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void anotherOrganizationsStaysNeverAppear() throws Exception {
        // seedOtherOrganization() added a ward, bed, active admission and a discharge in another organization.
        mockMvc.perform(clerk.apply(get("/api/v1/reports/admissions/active")))
                .andExpect(jsonPath("$.rows.length()", is(0)));
        mockMvc.perform(clerk.apply(get("/api/v1/reports/beds/occupancy")))
                .andExpect(jsonPath("$.rows.length()", is(1))) // only the Total row
                .andExpect(jsonPath("$.rows[0].totalBeds", is(0)))
                .andExpect(jsonPath("$.rows[0].occupancyPercent", is("0.0")));
        mockMvc.perform(clerk.apply(get("/api/v1/reports/discharges" + RANGE)))
                .andExpect(jsonPath("$.rows[1].count", is(0)));
    }

    @Test
    void activeAdmissionsCsvIsNamedWithTheOrganizationLocalDate() throws Exception {
        UUID ward = ipd.ward("CSVW");
        ipd.admitted(clerk, ipd.patient(), doctor, ipd.bed(ward, "V1"));
        // UTC+14 and UTC-12 never share a calendar date, so at most one equals the UTC date.
        for (String zone : new String[] {"Pacific/Kiritimati", "Etc/GMT+12"}) {
            fx.inOrg(org, () -> jdbc.sql("UPDATE hms.organization SET timezone = ? WHERE id = ?")
                    .params(zone, org).update());
            LocalDate today = LocalDate.now(ZoneId.of(zone));
            String csv = mockMvc.perform(clerk.apply(get("/api/v1/reports/admissions/active")).accept("text/csv"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"admissions-active-" + today + ".csv\""))
                    .andReturn().getResponse().getContentAsString();
            assertThat(csv).startsWith("admissionNo,uhid,patientName,wardName,bedNo,doctorName,status,admittedAt\r\n");
            assertThat(csv).contains("HMS-000501,Test Inpatient,Ward CSVW,V1,Dr. Test Ward,ADMITTED");
        }
    }

    @Test
    void withoutReportClinicalIs403() throws Exception {
        fx.role(org, "FINANCE_ONLY", "report:finance");
        var finance = fx.loggedIn(org, "finance", "FINANCE_ONLY");
        for (String path : new String[] {"admissions/active", "beds/occupancy", "discharges" + RANGE}) {
            mockMvc.perform(finance.apply(get("/api/v1/reports/" + path))).andExpect(status().isForbidden());
        }
    }

    /** Another organization with a ward, a bed, an active admission and a discharge on 2 Nov local. */
    private void seedOtherOrganization() throws Exception {
        UUID other = fx.organization();
        var otherAdmin = fx.withAllPermissions(other);
        var otherIpd = new IpdFixture(mockMvc, jdbc, fx, other, otherAdmin);
        UUID otherDoctor = otherIpd.doctor();
        UUID bed = otherIpd.bed(otherIpd.ward("OTHR"), "O1");
        otherIpd.admitted(otherAdmin, otherIpd.patient(), otherDoctor, bed);
        discharged(other, otherIpd.patient(), otherDoctor, "2026-11-01T18:31:00Z");
    }

    private void discharged(UUID organization, UUID patient, UUID practitioner, String at) {
        Instant dischargedAt = Instant.parse(at);
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.admission (id, organization_id, admission_no, patient_id, admitting_doctor_id,
                    admission_diagnosis, status, admitted_at, discharged_at, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'Synthetic diagnosis text', 'DISCHARGED', ?, ?, ?, now(), now())""")
                .params(UUID.randomUUID(), organization, "ADM-T-" + UUID.randomUUID().toString().substring(0, 8),
                        patient, practitioner, Timestamp.from(dischargedAt.minusSeconds(86400)),
                        Timestamp.from(dischargedAt), UUID.randomUUID()).update());
    }
}
