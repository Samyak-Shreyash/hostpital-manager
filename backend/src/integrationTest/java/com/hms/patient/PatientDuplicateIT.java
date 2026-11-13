package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * [E3-3] Duplicate detection (BR-P3, owner thresholds 0.45 same-DOB / 0.30 same-phone) and the audited "not a
 * duplicate" override. Synthetic data only: phones are +9103... (not allocatable), names are invented.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatientDuplicateIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String DOB = "1979-06-15";
    private static final String PHONE = "+910300000001";
    private static final String REASON = "Synthetic: different person, confirmed by guardian";

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

    private static String body(String name, String dob, String phone) {
        return "{\"fullName\":\"" + name + "\",\"sex\":\"F\",\"dateOfBirth\":\"" + dob + "\",\"phone\":\"" + phone
                + "\"}";
    }

    private static String confirmed(String json, String reason) {
        return json.substring(0, json.length() - 1) + ",\"confirmNotDuplicate\":true"
                + (reason == null ? "" : ",\"overrideReason\":\"" + reason + "\"") + "}";
    }

    private org.springframework.test.web.servlet.ResultActions register(AdminApiFixture.Session s, String json)
            throws Exception {
        return mockMvc.perform(s.apply(post(URL)).contentType(JSON).content(json));
    }

    private String created(AdminApiFixture.Session s, String json) throws Exception {
        return register(s, json).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private static UUID id(String response) {
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private double similarity(String a, String b) {
        return jdbc.sql("SELECT hms.similarity(?, ?)").params(a, b).query(Double.class).single();
    }

    private int patients(UUID organization) {
        return fx.inOrg(organization, () -> jdbc.sql("SELECT count(*) FROM hms.patient WHERE organization_id = ?")
                .param(organization).query(Integer.class).single());
    }

    // --- detection ----------------------------------------------------------------------------------------------

    @Test
    void sameDobAndSimilarNameIsSuspectedWithMaskedCandidatesAndNothingIsConsumed() throws Exception {
        String first = created(reception, body("Ananya Rao", DOB, PHONE));
        assertThat(similarity("Ananya Rao", "Ananya Rau")).isGreaterThanOrEqualTo(0.45);

        String response = register(reception, body("Ananya Rau", DOB, "+910300000099"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SUSPECTED")))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].id", is(id(first).toString())))
                .andExpect(jsonPath("$.candidates[0].uhid", is("HMS-000001")))
                .andExpect(jsonPath("$.candidates[0].fullName", is("Ananya Rao")))
                .andExpect(jsonPath("$.candidates[0].dateOfBirth", is(DOB)))
                .andExpect(jsonPath("$.candidates[0].phoneMasked", is("+91******0001")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(PHONE).doesNotContain("0300000001").doesNotContain("abha")
                .doesNotContain("address");
        double score = ((Number) JsonPath.read(response, "$.candidates[0].score")).doubleValue();
        assertThat(score).isBetween(0.45, 1.0);

        // The rejected registration rolled back: no row, and the next UHID is still HMS-000002.
        assertThat(patients(org)).isEqualTo(1);
        String other = created(reception, body("Kiran Mehta", "1990-01-01", "+910300000077"));
        assertThat((String) JsonPath.read(other, "$.uhid")).isEqualTo("HMS-000002");
    }

    @Test
    void samePhoneNeedsOnlyTheLowerThreshold() throws Exception {
        created(reception, body("Rohan Sharma", DOB, PHONE));
        double sim = similarity("Rohan Sharma", "Ronit Sharma Kapoor");
        assertThat(sim).as("between the two thresholds").isBetween(0.30, 0.45);

        // Same phone, different birth date: 0.30 applies, so this is suspected.
        register(reception, body("Ronit Sharma Kapoor", "1985-02-02", PHONE)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SUSPECTED")));
        // Same birth date, different phone: 0.45 applies, so it is not.
        created(reception, body("Ronit Sharma Kapoor", DOB, "+910300000055"));
    }

    @Test
    void dissimilarNamesOrNoSharedKeyAreNotDuplicates() throws Exception {
        created(reception, body("Ananya Rao", DOB, PHONE));
        assertThat(similarity("Ananya Rao", "Kiran Mehta")).isLessThan(0.30);
        created(reception, body("Kiran Mehta", DOB, PHONE)); // same DOB and phone, unrelated name (family member)
        created(reception, body("Ananya Rao", "1991-03-03", "+910300000044")); // same name, nothing else shared
        assertThat(patients(org)).isEqualTo(3);
    }

    @Test
    void phoneIsComparedNormalized() throws Exception {
        created(reception, body("Ananya Rao", "1990-01-01", PHONE));
        register(reception, body("Ananya Rao", "1991-01-01", "0300000001")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SUSPECTED")));
    }

    @Test
    void candidatesComeOnlyFromTheCallersOrganization() throws Exception {
        UUID other = fx.organization();
        created(fx.loggedIn(other, "reception", "RECEPTIONIST"), body("Ananya Rao", DOB, PHONE));
        created(reception, body("Ananya Rao", DOB, PHONE));
        mockMvc.perform(reception.apply(post(URL + "/duplicate-check")).contentType(JSON)
                .content(body("Ananya Rao", DOB, PHONE))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // --- override -----------------------------------------------------------------------------------------------

    @Test
    void confirmedOverrideRegistersRecordsTheReasonAndIsAuditedByFieldName() throws Exception {
        UUID existing = id(created(reception, body("Ananya Rao", DOB, PHONE)));
        UUID patient = id(created(reception, confirmed(body("Ananya Rao", DOB, PHONE), REASON)));

        var row = fx.inOrg(org, () -> jdbc.sql("""
                SELECT reason, candidate_ids::text AS candidates, overridden_by FROM hms.patient_duplicate_override
                WHERE organization_id = ? AND patient_id = ?
                """).params(org, patient).query((rs, n) -> List.of(rs.getString("reason"),
                rs.getString("candidates"), rs.getObject("overridden_by", UUID.class).toString())).single());
        assertThat(row.get(0)).isEqualTo(REASON);
        assertThat(row.get(1)).contains(existing.toString());
        assertThat(row.get(2)).isEqualTo(reception.userId().toString());

        assertThat(fx.auditActions(patient)).containsExactly("PATIENT_CREATED", "PATIENT_DUPLICATE_OVERRIDDEN");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? "
                + "AND action = 'PATIENT_DUPLICATE_OVERRIDDEN'").param(patient).query(String.class).single();
        assertThat(fields).contains("duplicateOverride.reason", "duplicateOverride.candidateIds");
        for (String audit : jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(patient).query(String.class).list()) {
            assertThat(audit).doesNotContain("guardian").doesNotContain("Ananya").doesNotContain(existing.toString());
        }
    }

    @Test
    void overrideRowsCannotBeChangedByTheApplicationRole() throws Exception {
        created(reception, body("Ananya Rao", DOB, PHONE));
        created(reception, confirmed(body("Ananya Rao", DOB, PHONE), REASON));
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.patient_duplicate_override SET reason = 'Synthetic changed'").update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void overrideNeedsAReasonAndThePermission() throws Exception {
        created(reception, body("Ananya Rao", DOB, PHONE));
        register(reception, confirmed(body("Ananya Rao", DOB, PHONE), null))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        register(reception, confirmed(body("Ananya Rao", DOB, PHONE), "  no "))
                .andExpect(status().isUnprocessableContent());

        fx.role(org, "CREATE_ONLY", "patient:create");
        AdminApiFixture.Session creator = fx.loggedIn(org, "creator", "CREATE_ONLY");
        register(creator, confirmed(body("Ananya Rao", DOB, PHONE), REASON)).andExpect(status().isForbidden());
        // Without suspected duplicates the permission is not needed, and confirmNotDuplicate is simply ignored.
        created(creator, confirmed(body("Kiran Mehta", "1990-01-01", "+910300000066"), REASON));
        assertThat(patients(org)).isEqualTo(2);
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.patient_duplicate_override")
                .query(Integer.class).single())).isZero();
    }

    // --- per-organization thresholds ----------------------------------------------------------------------------

    @Test
    void thresholdsAreOrganizationSettings() throws Exception {
        AdminApiFixture.Session admin = fx.loggedIn(org, "sysadmin", "SYSTEM_ADMIN");
        String settings = mockMvc.perform(admin.apply(get("/api/v1/organization"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicateDobThreshold", is(0.45)))
                .andExpect(jsonPath("$.duplicatePhoneThreshold", is(0.3)))
                .andReturn().getResponse().getContentAsString();
        int version = JsonPath.read(settings, "$.version");
        for (String bad : List.of("0", "1.5", "-0.1", "0.455")) {
            mockMvc.perform(admin.apply(patch("/api/v1/organization")).contentType(JSON)
                    .content("{\"version\":" + version + ",\"duplicateDobThreshold\":" + bad + "}"))
                    .andExpect(status().isUnprocessableContent());
        }
        mockMvc.perform(admin.apply(patch("/api/v1/organization")).contentType(JSON)
                .content("{\"version\":" + version + ",\"duplicateDobThreshold\":0.95}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.duplicateDobThreshold", is(0.95)));
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE organization_id = ? "
                + "AND action = 'ORGANIZATION_UPDATED'").param(org).query(String.class).single();
        assertThat(fields).contains("duplicateDobThreshold");

        // Similar but not identical names on the same DOB no longer count in this organization...
        created(reception, body("Ananya Rao", DOB, PHONE));
        created(reception, body("Ananya Rau", DOB, "+910300000098"));
        // ...while another organization keeps the defaults.
        UUID other = fx.organization();
        AdminApiFixture.Session otherReception = fx.loggedIn(other, "reception", "RECEPTIONIST");
        created(otherReception, body("Ananya Rao", DOB, PHONE));
        register(otherReception, body("Ananya Rau", DOB, "+910300000098")).andExpect(status().isConflict());
    }

    // --- pre-registration check ---------------------------------------------------------------------------------

    @Test
    void duplicateCheckReturnsCandidatesAuditsTheCountAndNeedsPatientCreate() throws Exception {
        created(reception, body("Ananya Rao", DOB, PHONE));
        mockMvc.perform(reception.apply(post(URL + "/duplicate-check")).contentType(JSON)
                .content(body("Ananya Rau", DOB, "+910300000099"))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].phoneMasked", is("+91******0001")));
        mockMvc.perform(reception.apply(post(URL + "/duplicate-check")).contentType(JSON)
                .content(body("Kiran Mehta", "1990-01-01", "+910300000099"))).andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        List<Integer> counts = jdbc.sql("SELECT result_count FROM hms.audit_event WHERE organization_id = ? AND "
                + "action = 'PATIENT_DUPLICATE_CHECK' ORDER BY occurred_at, id").param(org).query(Integer.class)
                .list();
        assertThat(counts).containsExactly(1, 0);

        AdminApiFixture.Session doctor = fx.loggedIn(org, "doctor", "DOCTOR");
        mockMvc.perform(doctor.apply(post(URL + "/duplicate-check")).contentType(JSON)
                .content(body("Ananya Rao", DOB, PHONE))).andExpect(status().isForbidden());
        mockMvc.perform(reception.apply(post(URL + "/duplicate-check")).contentType(JSON)
                .content("{\"fullName\":\"Ananya Rao\",\"phone\":\"" + PHONE + "\"}"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- concurrency --------------------------------------------------------------------------------------------

    /**
     * Two (here: eight) clerks register the same synthetic person at the same moment. Registrations of an
     * organization serialize on the UHID counter row lock taken before the duplicate query, so exactly one succeeds
     * and every other one sees it and gets DUPLICATE_SUSPECTED.
     */
    @Test
    void simultaneousRegistrationsOfTheSamePersonCreateOnlyOne() throws Exception {
        int threads = 8;
        List<AdminApiFixture.Session> clerks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            clerks.add(fx.loggedIn(org, "clerk" + i, "RECEPTIONIST"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<Integer>> results = new ArrayList<>();
            for (AdminApiFixture.Session clerk : clerks) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(clerk.apply(post(URL)).contentType(JSON)
                            .content(body("Meera Iyer", DOB, PHONE))).andReturn().getResponse();
                    if (response.getStatus() == 409) {
                        assertThat(response.getContentAsString()).contains("DUPLICATE_SUSPECTED");
                    }
                    return response.getStatus();
                }));
            }
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(patients(org)).isEqualTo(1);
    }
}
