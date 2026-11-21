package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.PlatformTransactionManager;

/** [E8-2] [E8-3] Admission with bed allocation, one active admission per patient. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class AdmissionIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/admissions";

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
    private UUID bed;
    private UUID patient;
    private UUID doctor;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("GENWARD");
        bed = ipd.bed(ward, "G-1");
        patient = ipd.patient();
        doctor = ipd.doctor();
    }

    @Test
    void admitReturnsTheAdmissionWithItsBedAndIsAuditedWithoutTheDiagnosis() throws Exception {
        UUID encounter = ipd.encounter(patient, doctor);
        String body = mockMvc.perform(clerk.apply(post(URL)).contentType(JSON)
                        .content(IpdFixture.admissionBody(patient, doctor, bed, encounter)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.admissionNo", matchesPattern("^ADM-\\d{4}-\\d{4}$")))
                .andExpect(jsonPath("$.status", is("ADMITTED")))
                .andExpect(jsonPath("$.patientId", is(patient.toString())))
                .andExpect(jsonPath("$.patient.uhid", notNullValue()))
                .andExpect(jsonPath("$.admittingDoctorId", is(doctor.toString())))
                .andExpect(jsonPath("$.sourceEncounterId", is(encounter.toString())))
                .andExpect(jsonPath("$.admissionDiagnosis", is("Synthetic diagnosis text")))
                .andExpect(jsonPath("$.admittedAt", notNullValue()))
                .andExpect(jsonPath("$.plannedDischargeAt", nullValue()))
                .andExpect(jsonPath("$.dischargedAt", nullValue()))
                .andExpect(jsonPath("$.currentBed.id", is(bed.toString())))
                .andExpect(jsonPath("$.currentBed.bedNo", is("G-1")))
                .andExpect(jsonPath("$.currentBed.ward", is("Ward GENWARD")))
                .andExpect(jsonPath("$.version", is(0)))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(clerk.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(jsonPath("$.wards[0].occupied", is(1)))
                .andExpect(jsonPath("$.wards[0].available", is(0)))
                .andExpect(jsonPath("$.wards[0].beds[0].admissionId", is(id.toString())));
        mockMvc.perform(clerk.apply(get(URL + "/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentBed.bedNo", is("G-1")));
        mockMvc.perform(clerk.apply(get(URL + "/" + id + "/bed-assignments"))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bedId", is(bed.toString())))
                .andExpect(jsonPath("$[0].wardName", is("Ward GENWARD")))
                .andExpect(jsonPath("$[0].dailyRateServiceId", is(ipd.rateService().toString())))
                .andExpect(jsonPath("$[0].releasedAt", nullValue()));

        assertThat(fx.auditActions(id)).containsExactly("ADMISSION_CREATED", "BED_ASSIGNED", "ADMISSION_VIEWED",
                "ADMISSION_VIEWED");
        List<String> rows = jdbc.sql("SELECT CAST(changed_fields AS text) FROM hms.audit_event WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(rows).noneMatch(r -> r != null && r.contains("Synthetic diagnosis"));
    }

    @Test
    void admissionNoFormatAndIncrements() throws Exception {
        UUID bed2 = ipd.bed(ward, "G-2");
        String first = ipd.admit(clerk, patient, doctor, bed).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        String second = ipd.admit(clerk, ipd.patient(), doctor, bed2).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        String a = JsonPath.read(first, "$.admissionNo");
        String b = JsonPath.read(second, "$.admissionNo");
        assertThat(a).matches("^ADM-\\d{4}-\\d{4}$");
        assertThat(b).matches("^ADM-\\d{4}-\\d{4}$");
        assertThat(Integer.parseInt(b.substring(9))).isEqualTo(Integer.parseInt(a.substring(9)) + 1);
        assertThat(b.substring(0, 9)).isEqualTo(a.substring(0, 9));
    }

    @Test
    void secondActiveAdmissionIs409PatientAlreadyAdmitted() throws Exception {
        UUID bed2 = ipd.bed(ward, "G-2");
        ipd.admitted(clerk, patient, doctor, bed);
        ipd.admit(clerk, patient, doctor, bed2).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("PATIENT_ALREADY_ADMITTED")));
        assertThat(ipd.openAssignments(bed2)).isZero();
    }

    @Test
    void anOccupiedBedIs409BedNotAvailable() throws Exception {
        ipd.admitted(clerk, patient, doctor, bed);
        ipd.admit(clerk, ipd.patient(), doctor, bed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BED_NOT_AVAILABLE")));
    }

    @Test
    void outOfServiceBedIs409BedNotAvailable() throws Exception {
        mockMvc.perform(clerk.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}")).andExpect(status().isOk());
        ipd.admit(clerk, patient, doctor, bed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BED_NOT_AVAILABLE")));
    }

    @Test
    void aBedInAnInactiveWardIs409BedNotAvailable() throws Exception {
        mockMvc.perform(clerk.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"INACTIVE\"}")).andExpect(status().isOk());
        ipd.admit(clerk, patient, doctor, bed).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BED_NOT_AVAILABLE")));
    }

    @Test
    void outOfServiceWhileOccupiedIs409BedOccupied() throws Exception {
        ipd.admitted(clerk, patient, doctor, bed);
        mockMvc.perform(clerk.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                        .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BED_OCCUPIED")));
    }

    @Test
    void cancelReleasesBed() throws Exception {
        UUID id = ipd.admitted(clerk, patient, doctor, bed);
        mockMvc.perform(clerk.apply(post(URL + "/" + id + "/cancel")).contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.currentBed", nullValue()))
                .andExpect(jsonPath("$.version", is(1)));
        assertThat(ipd.openAssignments(bed)).isZero();
        mockMvc.perform(clerk.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(jsonPath("$.wards[0].available", is(1)));
        mockMvc.perform(clerk.apply(get(URL + "/" + id + "/bed-assignments")))
                .andExpect(jsonPath("$[0].releasedAt", notNullValue()));
        // Cancelled twice: the state machine refuses; the patient may be admitted again.
        mockMvc.perform(clerk.apply(post(URL + "/" + id + "/cancel")).contentType(JSON).content("{\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        ipd.admitted(clerk, patient, doctor, bed);
        assertThat(fx.auditActions(id)).contains("ADMISSION_CANCELLED", "BED_RELEASED");
    }

    @Test
    void updateDiagnosisNeedsTheCurrentVersion() throws Exception {
        UUID id = ipd.admitted(clerk, patient, doctor, bed);
        mockMvc.perform(clerk.apply(patch(URL + "/" + id)).contentType(JSON)
                        .content("{\"version\":0,\"admissionDiagnosis\":\"Revised synthetic text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.admissionDiagnosis", is("Revised synthetic text")))
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(clerk.apply(patch(URL + "/" + id)).contentType(JSON)
                        .content("{\"version\":0,\"admissionDiagnosis\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(clerk.apply(post(URL + "/" + id + "/cancel")).contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(clerk.apply(patch(URL + "/" + id)).contentType(JSON).content("{\"version\":1}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(fx.auditActions(id)).contains("ADMISSION_UPDATED");
    }

    @Test
    void listFiltersByStatusWardAndPatient() throws Exception {
        UUID otherWard = ipd.ward("ICU");
        UUID icuBed = ipd.bed(otherWard, "I-1");
        UUID other = ipd.patient();
        UUID a = ipd.admitted(clerk, patient, doctor, bed);
        UUID b = ipd.admitted(clerk, other, doctor, icuBed);
        mockMvc.perform(clerk.apply(get(URL))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2)));
        mockMvc.perform(clerk.apply(get(URL)).param("wardId", otherWard.toString()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(b.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
        mockMvc.perform(clerk.apply(get(URL)).param("patientId", patient.toString()))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(a.toString())));
        mockMvc.perform(clerk.apply(post(URL + "/" + a + "/cancel")).contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(clerk.apply(get(URL)).param("status", "ADMITTED"))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(b.toString())));
        mockMvc.perform(clerk.apply(get(URL)).param("status", "BOGUS")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void sourceEncounterMustBelongToThePatient() throws Exception {
        UUID encounter = ipd.encounter(ipd.patient(), doctor);
        mockMvc.perform(clerk.apply(post(URL)).contentType(JSON)
                        .content(IpdFixture.admissionBody(patient, doctor, bed, encounter)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(clerk.apply(post(URL)).contentType(JSON).content("{\"patientId\":\"" + patient + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingAdmissionCreateIs403() throws Exception {
        fx.role(org, "READER", "admission:read", "bed:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READER");
        ipd.admit(reader, patient, doctor, bed).andExpect(status().isForbidden());
        UUID id = ipd.admitted(clerk, patient, doctor, bed);
        mockMvc.perform(reader.apply(get(URL + "/" + id))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(patch(URL + "/" + id)).contentType(JSON)
                .content("{\"version\":0,\"admissionDiagnosis\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(post(URL + "/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":0}")).andExpect(status().isForbidden());
        fx.role(org, "NOTHING", "bed:read");
        AdminApiFixture.Session nobody = fx.loggedIn(org, "nobody", "NOTHING");
        mockMvc.perform(nobody.apply(get(URL))).andExpect(status().isForbidden());
        mockMvc.perform(nobody.apply(get(URL + "/" + id + "/bed-assignments"))).andExpect(status().isForbidden());
    }

    @Test
    void otherOrgPatientDoctorBedAndAdmissionAre404() throws Exception {
        UUID otherOrg = fx.organization();
        AdminApiFixture.Session other = fx.withAllPermissions(otherOrg);
        IpdFixture otherIpd = new IpdFixture(mockMvc, jdbc, fx, otherOrg, other);
        UUID foreignBed = otherIpd.bed(otherIpd.ward("FOREIGN"), "F-1");
        UUID foreignPatient = otherIpd.patient();
        UUID foreignDoctor = otherIpd.doctor();
        UUID foreignEncounter = otherIpd.encounter(foreignPatient, foreignDoctor);

        ipd.admit(clerk, foreignPatient, doctor, bed).andExpect(status().isNotFound());
        ipd.admit(clerk, patient, foreignDoctor, bed).andExpect(status().isNotFound());
        ipd.admit(clerk, patient, doctor, foreignBed).andExpect(status().isNotFound());
        mockMvc.perform(clerk.apply(post(URL)).contentType(JSON)
                        .content(IpdFixture.admissionBody(patient, doctor, bed, foreignEncounter)))
                .andExpect(status().isNotFound());

        UUID id = ipd.admitted(clerk, patient, doctor, bed);
        mockMvc.perform(other.apply(get(URL + "/" + id))).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(get(URL + "/" + id + "/bed-assignments"))).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(patch(URL + "/" + id)).contentType(JSON)
                .content("{\"version\":0,\"admissionDiagnosis\":\"X\"}")).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(post(URL + "/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":0}")).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(get(URL))).andExpect(jsonPath("$.totalItems", is(0)));
    }

    @Test
    void theDatabaseAllowsOnlyReleasingAnAssignment() {
        assertThat(sqlState("UPDATE hms.bed_assignment SET bed_id = bed_id")).isEqualTo("42501");
        assertThat(sqlState("DELETE FROM hms.bed_assignment")).isEqualTo("42501");
        assertThat(sqlState("DELETE FROM hms.admission")).isEqualTo("42501");
    }

    private String sqlState(String sql) {
        try {
            fx.inOrg(org, () -> jdbc.sql(sql).update());
            return null;
        } catch (RuntimeException e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof java.sql.SQLException s) {
                    return s.getSQLState();
                }
            }
            return "UNKNOWN";
        }
    }
}
