package com.hms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import java.sql.Timestamp;
import java.time.Instant;
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

/** [E9-1] Operational reports: local-date bucketing, CSV, validation, permission, audit. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class OperationalReportsIT extends PostgresIT {

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
    private AdminApiFixture.Session reporter;
    private int uhid;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "REPORTER", "report:clinical");
        fx.role(org, "FINANCE_ONLY", "report:finance");
        reporter = fx.loggedIn(org, "reporter", "REPORTER");
    }

    @Test
    void registrationsAreBucketedByTheOrganizationLocalDateAndZeroFilled() throws Exception {
        patient(org, "2026-11-01T18:29:00Z"); // 23:59 on 1 Nov in Asia/Kolkata
        patient(org, "2026-11-01T18:31:00Z"); // 00:01 on 2 Nov
        UUID other = fx.organization();
        patient(other, "2026-11-01T18:31:00Z"); // another organization: never counted

        mockMvc.perform(reporter.apply(get("/api/v1/reports/registrations/daily" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report", is("registrations-daily")))
                .andExpect(jsonPath("$.from", is("2026-11-01")))
                .andExpect(jsonPath("$.columns[0]", is("date")))
                .andExpect(jsonPath("$.rows.length()", is(3)))
                .andExpect(jsonPath("$.rows[0].date", is("2026-11-01")))
                .andExpect(jsonPath("$.rows[0].count", is(1)))
                .andExpect(jsonPath("$.rows[1].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[1].count", is(1)))
                .andExpect(jsonPath("$.rows[2].date", is("2026-11-03")))
                .andExpect(jsonPath("$.rows[2].count", is(0)));
    }

    @Test
    void reportTotalMatchesTheSeededPatientCount() throws Exception {
        for (int i = 0; i < 5; i++) {
            patient(org, "2026-11-02T05:00:00Z");
        }
        int expected = fx.inOrg(org, () -> jdbc.sql(
                "SELECT count(*) FROM hms.patient WHERE organization_id = ?").param(org).query(Integer.class).single());
        String body = mockMvc.perform(reporter.apply(get("/api/v1/reports/registrations/daily" + RANGE)))
                .andReturn().getResponse().getContentAsString();
        int total = com.jayway.jsonpath.JsonPath.<java.util.List<Integer>>read(body, "$.rows[*].count").stream()
                .mapToInt(Integer::intValue).sum();
        assertThat(total).isEqualTo(expected).isEqualTo(5);
    }

    @Test
    void appointmentsAreGroupedByDoctorAndStatus() throws Exception {
        UUID doctor = doctor(org, "Dr. Report A");
        UUID patient = patient(org, "2026-10-01T00:00:00Z");
        appointment(org, patient, doctor, "2026-11-02T04:00:00Z", "SCHEDULED");
        appointment(org, patient, doctor, "2026-11-02T05:00:00Z", "SCHEDULED");
        appointment(org, patient, doctor, "2026-11-02T06:00:00Z", "CANCELLED");

        mockMvc.perform(reporter.apply(get("/api/v1/reports/appointments" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()", is(2)))
                .andExpect(jsonPath("$.rows[0].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[0].practitionerId", is(doctor.toString())))
                .andExpect(jsonPath("$.rows[0].practitionerName", is("Dr. Report A")))
                .andExpect(jsonPath("$.rows[0].status", is("CANCELLED")))
                .andExpect(jsonPath("$.rows[0].count", is(1)))
                .andExpect(jsonPath("$.rows[1].status", is("SCHEDULED")))
                .andExpect(jsonPath("$.rows[1].count", is(2)));
    }

    @Test
    void opdConsultationsCountOnlyFinalizedEncountersOnTheFinalizedDate() throws Exception {
        UUID doctor = doctor(org, "Dr. Report B");
        UUID patient = patient(org, "2026-10-01T00:00:00Z");
        encounter(org, patient, doctor, "2026-11-02T05:00:00Z"); // finalized
        encounter(org, patient, doctor, null); // open: not counted

        mockMvc.perform(reporter.apply(get("/api/v1/reports/opd-consultations" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()", is(1)))
                .andExpect(jsonPath("$.rows[0].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[0].practitionerName", is("Dr. Report B")))
                .andExpect(jsonPath("$.rows[0].count", is(1)));
    }

    @Test
    void csvIsServedOnAcceptWithAFilename() throws Exception {
        patient(org, "2026-11-01T18:31:00Z");
        String csv = mockMvc.perform(reporter.apply(get("/api/v1/reports/registrations/daily" + RANGE))
                        .accept("text/csv"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"registrations-daily-2026-11-01-2026-11-03.csv\""))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).startsWith("date,count\r\n2026-11-01,0\r\n2026-11-02,1\r\n");
    }

    @Test
    void invalidRangeIs422() throws Exception {
        mockMvc.perform(reporter.apply(get("/api/v1/reports/registrations/daily?from=2026-11-03&to=2026-11-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(reporter.apply(get("/api/v1/reports/appointments?from=2026-01-01&to=2027-01-02")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(reporter.apply(get("/api/v1/reports/opd-consultations")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void withoutReportClinicalIs403() throws Exception {
        var finance = fx.loggedIn(org, "finance", "FINANCE_ONLY");
        for (String path : new String[] {"registrations/daily", "appointments", "opd-consultations"}) {
            mockMvc.perform(finance.apply(get("/api/v1/reports/" + path + RANGE))).andExpect(status().isForbidden());
        }
    }

    @Test
    void everyRunWritesOneReportViewedAuditEvent() throws Exception {
        mockMvc.perform(reporter.apply(get("/api/v1/reports/appointments" + RANGE))).andExpect(status().isOk());
        mockMvc.perform(reporter.apply(get("/api/v1/reports/appointments" + RANGE)).accept(MediaType.parseMediaType(
                "text/csv"))).andExpect(status().isOk());
        var rows = jdbc.sql("""
                SELECT array_to_string(changed_fields, ',') FROM hms.audit_event
                WHERE organization_id = ? AND action = 'REPORT_VIEWED' AND entity_type = 'REPORT'
                  AND entity_id IS NULL ORDER BY occurred_at, id""").param(org).query(String.class).list();
        assertThat(rows).containsExactly("from,to", "format,from,to");
    }

    private UUID patient(UUID organization, String createdAt) {
        UUID id = UUID.randomUUID();
        String uhidValue = String.format("HMS-%06d", 700 + ++uhid);
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Reportee', 'U', DATE '1990-01-01', '+910000000066', ?, ?, ?)""")
                .params(id, organization, uhidValue, UUID.randomUUID(), Timestamp.from(Instant.parse(createdAt)),
                        Timestamp.from(Instant.parse(createdAt))).update());
        return id;
    }

    private UUID doctor(UUID organization, String name) {
        UUID id = UUID.randomUUID();
        UUID user = fx.user(organization, fx.uniqueEmail("doctor"));
        UUID specialty = UUID.randomUUID();
        fx.inOrg(organization, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Test Medicine', now(), now())").params(specialty, organization,
                    "R" + specialty.toString().substring(0, 8).toUpperCase()).update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name,"
                    + " registration_no, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, ?, ?, now(), now())")
                    .params(id, organization, user, name, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user)
                    .update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(organization, id, specialty).update();
        });
        return id;
    }

    private void appointment(UUID organization, UUID patient, UUID doctor, String start, String status) {
        Instant at = Instant.parse(start);
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(UUID.randomUUID(), organization, patient, doctor, Timestamp.from(at),
                        Timestamp.from(at.plusSeconds(900)), status, UUID.randomUUID()).update());
    }

    /** finalizedAt null = an OPEN encounter. */
    private void encounter(UUID organization, UUID patient, UUID doctor, String finalizedAt) {
        UUID by = UUID.randomUUID();
        fx.inOrg(organization, () -> finalizedAt == null
                ? jdbc.sql("""
                        INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                            created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                        .params(UUID.randomUUID(), organization, patient, doctor, by).update()
                : jdbc.sql("""
                        INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, status,
                            started_at, finalized_at, finalized_by, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'FINALIZED', ?, ?, ?, ?, now(), now())""")
                        .params(UUID.randomUUID(), organization, patient, doctor,
                                Timestamp.from(Instant.parse(finalizedAt).minusSeconds(600)),
                                Timestamp.from(Instant.parse(finalizedAt)), by, by).update());
    }
}
