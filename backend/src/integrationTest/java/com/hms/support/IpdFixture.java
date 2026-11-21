package com.hms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Wards, beds, patients, doctors and admissions for the IPD ITs ([E8-2]). Synthetic data only. */
public final class IpdFixture {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

    private final MockMvc mockMvc;
    private final JdbcClient jdbc;
    private final AdminApiFixture fx;
    private final UUID org;
    private final AdminApiFixture.Session admin;
    private final UUID rateService;
    private int patients;

    /** {@code admin} needs service, ward and bed management (FACILITY_ADMIN). */
    public IpdFixture(MockMvc mockMvc, JdbcClient jdbc, AdminApiFixture fx, UUID org, AdminApiFixture.Session admin)
            throws Exception {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.fx = fx;
        this.org = org;
        this.admin = admin;
        String body = mockMvc.perform(admin.apply(post("/api/v1/services")).contentType(JSON)
                        .content("{\"code\":\"ROOM_" + UUID.randomUUID().toString().substring(0, 6).toUpperCase()
                                + "\",\"name\":\"Room charge\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        this.rateService = UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public UUID rateService() {
        return rateService;
    }

    public UUID ward(String code) throws Exception {
        String body = mockMvc.perform(admin.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Ward " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public UUID bed(UUID ward, String bedNo) throws Exception {
        String body = mockMvc.perform(admin.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content("{\"bedNo\":\"" + bedNo + "\",\"dailyRateServiceId\":\"" + rateService + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public UUID patient() {
        UUID id = UUID.randomUUID();
        String uhid = String.format("HMS-%06d", 500 + ++patients);
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Inpatient', 'U', DATE '1990-01-01', '+910000000055', ?, now(), now())""")
                .params(id, org, uhid, UUID.randomUUID()).update());
        return id;
    }

    public UUID doctor() {
        UUID id = UUID.randomUUID();
        UUID user = fx.user(org, fx.uniqueEmail("doctor"));
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Test Medicine', now(), now())").params(specialty, org,
                    "G" + specialty.toString().substring(0, 8).toUpperCase()).update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name,"
                    + " registration_no, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Dr. Test Ward', ?, ?, now(), now())")
                    .params(id, org, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user).update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(org, id, specialty).update();
        });
        return id;
    }

    public UUID encounter(UUID patient, UUID doctor) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                    created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                .params(id, org, patient, doctor, admin.userId()).update());
        return id;
    }

    public static String admissionBody(UUID patient, UUID doctor, UUID bed, UUID encounter) {
        return "{\"patientId\":\"" + patient + "\",\"admittingDoctorId\":\"" + doctor + "\",\"bedId\":\"" + bed
                + "\",\"admissionDiagnosis\":\"Synthetic diagnosis text\""
                + (encounter == null ? "" : ",\"sourceEncounterId\":\"" + encounter + "\"") + "}";
    }

    public ResultActions admit(AdminApiFixture.Session who, UUID patient, UUID doctor, UUID bed) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/admissions")).contentType(JSON)
                .content(admissionBody(patient, doctor, bed, null)));
    }

    /** Admits and returns the admission id. */
    public UUID admitted(AdminApiFixture.Session who, UUID patient, UUID doctor, UUID bed) throws Exception {
        String body = admit(who, patient, doctor, bed).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public long openAssignments(UUID bed) {
        return fx.inOrg(org, () -> jdbc.sql(
                "SELECT count(*) FROM hms.bed_assignment WHERE bed_id = ? AND released_at IS NULL")
                .param(bed).query(Long.class).single());
    }
}
