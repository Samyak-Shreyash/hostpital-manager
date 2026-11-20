package com.hms.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.clinical.api.EncounterQuery;
import com.hms.clinical.api.EncounterQuery.EncounterSummary;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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

/** [E6-1] Encounter recording: start, observations, notes, diagnoses, plan, follow-up, reads. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class EncounterIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/encounters";
    private static final String COMPLAINT = "Synthetic complaint zebra-stripes";

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

    @Autowired
    EncounterQuery encounterQuery;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session doctor;
    private UUID doctorId;
    private AdminApiFixture.Session otherDoctor;
    private AdminApiFixture.Session supervisor;
    private AdminApiFixture.Session nurse;
    private AdminApiFixture.Session reader;
    private UUID patient;
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "CLIN", "encounter:create", "encounter:read", "encounter:update", "observation:write");
        fx.role(org, "CLIN_ANY", "encounter:create", "encounter:read", "encounter:update", "encounter:update-any",
                "observation:write");
        fx.role(org, "VITALS", "observation:write");
        fx.role(org, "READER", "encounter:read");
        String doctorEmail = fx.uniqueEmail("doctor");
        UUID doctorUser = fx.user(org, doctorEmail, "CLIN");
        doctorId = insertPractitioner(doctorUser);
        doctor = fx.login(doctorUser, doctorEmail);
        String otherEmail = fx.uniqueEmail("doctor");
        UUID otherUser = fx.user(org, otherEmail, "CLIN");
        insertPractitioner(otherUser);
        otherDoctor = fx.login(otherUser, otherEmail);
        supervisor = fx.loggedIn(org, "supervisor", "CLIN_ANY");
        nurse = fx.loggedIn(org, "nurse", "VITALS");
        reader = fx.loggedIn(org, "reader", "READER");
        patient = insertPatient(org, "HMS-000001");
    }

    @Test
    void startingFromACheckedInAppointmentMovesItInConsultationAndIsAudited() throws Exception {
        UUID appointment = insertAppointment(doctorId, patient, "CHECKED_IN");
        String body = start(doctor, "{\"appointmentId\":\"" + appointment + "\",\"chiefComplaint\":\"" + COMPLAINT
                + "\"}").andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("OPEN")))
                .andExpect(jsonPath("$.version", is(0))).andExpect(jsonPath("$.patient.uhid", is("HMS-000001")))
                .andExpect(jsonPath("$.patient.id", is(patient.toString())))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(appointmentStatus(appointment)).isEqualTo("IN_CONSULTATION");
        assertThat(fx.auditActions(id)).containsExactly("ENCOUNTER_STARTED");
        Optional<EncounterSummary> summary = fx.inOrg(org, () -> encounterQuery.find(org, id));
        assertThat(summary).isPresent();
        assertThat(summary.get().patientId()).isEqualTo(patient);
        assertThat(summary.get().practitionerId()).isEqualTo(doctorId);
        assertThat(summary.get().status()).isEqualTo("OPEN");
        assertThat(fx.inOrg(org, () -> encounterQuery.find(UUID.randomUUID(), id))).isEmpty();
    }

    @Test
    void auditNeverCarriesTheComplaintText() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":0,\"text\":\"Synthetic symptoms quokka\"}")
                .andExpect(status().isOk());
        List<String> rows = jdbc.sql("SELECT action || ' ' || array_to_string(changed_fields, ',')"
                + " FROM hms.audit_event WHERE entity_id = ?").param(id).query(String.class).list();
        assertThat(rows).isNotEmpty();
        assertThat(String.join("|", rows)).doesNotContain("zebra").doesNotContain("quokka");
    }

    @Test
    void anAppointmentThatIsNotCheckedInCannotStartAnEncounter() throws Exception {
        UUID appointment = insertAppointment(doctorId, patient, "SCHEDULED");
        start(doctor, "{\"appointmentId\":\"" + appointment + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(appointmentStatus(appointment)).isEqualTo("SCHEDULED");
        assertThat(encounterCount()).isZero();
        // A second start for the same appointment is refused too (the first one moved it on).
        UUID checkedIn = insertAppointment(doctorId, patient, "CHECKED_IN");
        start(doctor, "{\"appointmentId\":\"" + checkedIn + "\"}").andExpect(status().isCreated());
        start(doctor, "{\"appointmentId\":\"" + checkedIn + "\"}").andExpect(status().isConflict());
        assertThat(encounterCount()).isEqualTo(1);
    }

    @Test
    void onlyTheAppointmentsDoctorOrAnUpdateAnyHolderMayStartIt() throws Exception {
        UUID appointment = insertAppointment(doctorId, patient, "CHECKED_IN");
        start(otherDoctor, "{\"appointmentId\":\"" + appointment + "\"}").andExpect(status().isForbidden());
        assertThat(appointmentStatus(appointment)).isEqualTo("CHECKED_IN");
        start(supervisor, "{\"appointmentId\":\"" + appointment + "\"}").andExpect(status().isCreated());
    }

    @Test
    void anAdHocEncounterNeedsAPatientAndAPractitionerUser() throws Exception {
        start(doctor, "{\"patientId\":\"" + patient + "\",\"chiefComplaint\":\"" + COMPLAINT + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("OPEN")));
        start(doctor, "{\"chiefComplaint\":\"x\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        start(doctor, "{\"patientId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PATIENT_NOT_AVAILABLE")));
        // The supervisor is no practitioner.
        start(supervisor, "{\"patientId\":\"" + patient + "\"}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("NOT_A_PRACTITIONER")));
        start(doctor, "{\"appointmentId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
    }

    @Test
    void aNonTreatingDoctorIsForbiddenAndUpdateAnyAllows() throws Exception {
        UUID id = startAdHoc(doctor);
        String notes = "{\"version\":0,\"text\":\"Synthetic examination\"}";
        putSection(otherDoctor, id, "notes/EXAMINATION", notes).andExpect(status().isForbidden());
        putSection(supervisor, id, "notes/EXAMINATION", notes).andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(1)));
        putSection(doctor, id, "notes/ASSESSMENT", "{\"version\":1,\"text\":\"Synthetic assessment\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(2)));
    }

    @Test
    void permissionsGateTheEndpoints() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(reader, id, "notes/SYMPTOMS", "{\"version\":0,\"text\":\"t\"}").andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(get(URL + "/" + id))).andExpect(status().isOk());
        mockMvc.perform(nurse.apply(get(URL + "/" + id))).andExpect(status().isForbidden());
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":0,\"text\":\"Synthetic symptoms\"}").andExpect(status().isOk());
        // Without encounter:read the nurse gets only what was written, never the rest of the record.
        putSection(nurse, id, "observations", "{\"version\":1,\"observations\":[{\"kind\":\"PULSE\",\"value\":\"72\","
                + "\"unit\":\"bpm\"}]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id.toString()))).andExpect(jsonPath("$.version", is(2)))
                .andExpect(jsonPath("$.observations", hasSize(1))).andExpect(jsonPath("$.warnings", hasSize(0)))
                .andExpect(jsonPath("$.notes").doesNotExist()).andExpect(jsonPath("$.diagnoses").doesNotExist())
                .andExpect(jsonPath("$.treatmentPlan").doesNotExist()).andExpect(jsonPath("$.followUp").doesNotExist())
                .andExpect(jsonPath("$.chiefComplaint").doesNotExist()).andExpect(jsonPath("$.patient").doesNotExist());
        // A caller with encounter:read still gets the full view.
        putSection(doctor, id, "observations", "{\"version\":2,\"observations\":[]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", hasSize(1))).andExpect(jsonPath("$.patient.uhid").exists())
                .andExpect(jsonPath("$.diagnoses").exists());
        putSection(reader, id, "observations", "{\"version\":3,\"observations\":[]}").andExpect(status().isForbidden());
    }

    @Test
    void observationsAreReplacedAsASetAndValidatedAgainstHardLimits() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "observations", obs(0, "{\"kind\":\"TEMP_C\",\"value\":\"-1\",\"unit\":\"C\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        putSection(doctor, id, "observations", obs(0, "{\"kind\":\"BP_SYS\",\"value\":\"301\",\"unit\":\"mmHg\"}"))
                .andExpect(status().isUnprocessableEntity());
        putSection(doctor, id, "observations", obs(0, "{\"kind\":\"PULSE\",\"value\":\"72\",\"unit\":\"bpm\"},"
                + "{\"kind\":\"PULSE\",\"value\":\"73\",\"unit\":\"bpm\"}")).andExpect(status().isUnprocessableEntity());
        putSection(doctor, id, "observations", obs(0, "{\"kind\":\"NOPE\",\"value\":\"1\",\"unit\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity());
        // Nothing was stored and the version did not move.
        mockMvc.perform(doctor.apply(get(URL + "/" + id))).andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.observations", hasSize(0)));

        putSection(doctor, id, "observations", obs(0, "{\"kind\":\"BP_SYS\",\"value\":\"120\",\"unit\":\"mmHg\"},"
                + "{\"kind\":\"TEMP_C\",\"value\":\"38.4\",\"unit\":\"C\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observations", hasSize(2)))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        // An implausible but possible value is accepted with a warning.
        putSection(doctor, id, "observations", obs(1, "{\"kind\":\"TEMP_C\",\"value\":\"42\",\"unit\":\"C\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.observations", hasSize(1)))
                .andExpect(jsonPath("$.warnings", hasSize(1))).andExpect(jsonPath("$.warnings[0].kind", is("TEMP_C")));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.encounter_observation"
                + " WHERE encounter_id = ?").param(id).query(Integer.class).single())).isEqualTo(3); // history stays
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.encounter_observation"
                + " WHERE encounter_id = ? AND replaced_at IS NULL").param(id).query(Integer.class).single()))
                .isEqualTo(1);
    }

    @Test
    void theWholeRecordIsStoredAndReadBack() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":0,\"text\":\"Synthetic symptoms\"}").andExpect(status().isOk());
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":1,\"text\":\"Synthetic symptoms v2\"}")
                .andExpect(status().isOk());
        putSection(doctor, id, "notes/ADDENDUM", "{\"version\":2,\"text\":\"x\"}").andExpect(status().isUnprocessableEntity());
        putSection(doctor, id, "notes/BOGUS", "{\"version\":2,\"text\":\"x\"}").andExpect(status().isUnprocessableEntity());
        putSection(doctor, id, "diagnoses", "{\"version\":2,\"diagnoses\":[{\"code\":\"J06.9\","
                + "\"description\":\"Synthetic infection\",\"primary\":true},{\"description\":\"Synthetic other\","
                + "\"primary\":false}]}").andExpect(status().isOk());
        putSection(doctor, id, "diagnoses", "{\"version\":3,\"diagnoses\":[{\"description\":\"a\",\"primary\":true},"
                + "{\"description\":\"b\",\"primary\":true}]}").andExpect(status().isUnprocessableEntity());
        putSection(doctor, id, "treatment-plan", "{\"version\":3,\"text\":\"Synthetic plan\"}").andExpect(status().isOk());
        putSection(doctor, id, "follow-up", "{\"version\":4,\"date\":\"2030-01-15\",\"instructions\":\"Synthetic return\"}")
                .andExpect(status().isOk());
        mockMvc.perform(doctor.apply(get(URL + "/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(5)))
                .andExpect(jsonPath("$.chiefComplaint", is(COMPLAINT)))
                .andExpect(jsonPath("$.notes", hasSize(1)))
                .andExpect(jsonPath("$.notes[0].kind", is("SYMPTOMS")))
                .andExpect(jsonPath("$.notes[0].text", is("Synthetic symptoms v2")))
                .andExpect(jsonPath("$.diagnoses", hasSize(2)))
                .andExpect(jsonPath("$.diagnoses[0].primary", is(true)))
                .andExpect(jsonPath("$.treatmentPlan", is("Synthetic plan")))
                .andExpect(jsonPath("$.followUp.date", is("2030-01-15")))
                .andExpect(jsonPath("$.followUp.instructions", is("Synthetic return")));
        assertThat(fx.auditActions(id)).containsExactly("ENCOUNTER_STARTED", "ENCOUNTER_NOTE_UPDATED",
                "ENCOUNTER_NOTE_UPDATED", "ENCOUNTER_DIAGNOSES_UPDATED", "ENCOUNTER_TREATMENT_PLAN_UPDATED",
                "ENCOUNTER_FOLLOW_UP_UPDATED", "ENCOUNTER_VIEWED");
    }

    @Test
    void aStaleVersionIsAConflictAndAFinalizedEncounterIsLocked() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "treatment-plan", "{\"version\":0,\"text\":\"Synthetic plan\"}").andExpect(status().isOk());
        putSection(doctor, id, "treatment-plan", "{\"version\":0,\"text\":\"Synthetic plan 2\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        putSection(doctor, id, "treatment-plan", "{\"text\":\"no version\"}").andExpect(status().isUnprocessableEntity());
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.encounter SET status = 'FINALIZED', finalized_at = now(),"
                + " finalized_by = ? WHERE id = ?").params(doctor.userId(), id).update());
        putSection(doctor, id, "treatment-plan", "{\"version\":1,\"text\":\"late\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        putSection(doctor, id, "observations", obs(1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
    }

    @Test
    void readingIsAuditedAndHistoryListsThePatientsEncounters() throws Exception {
        UUID first = startAdHoc(doctor);
        UUID second = startAdHoc(doctor);
        mockMvc.perform(doctor.apply(get(URL + "/" + first))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(first.toString())));
        assertThat(fx.auditActions(first)).containsExactly("ENCOUNTER_STARTED", "ENCOUNTER_VIEWED");
        mockMvc.perform(doctor.apply(get("/api/v1/patients/" + patient + "/encounters"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.items[0].id", is(second.toString())))
                .andExpect(jsonPath("$.totalItems", is(2)));
        assertThat(fx.auditActions(patient)).contains("ENCOUNTER_HISTORY_VIEWED");
        mockMvc.perform(doctor.apply(get("/api/v1/patients/" + UUID.randomUUID() + "/encounters")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherOrganizationsEncounterIsNotFound() throws Exception {
        UUID id = startAdHoc(doctor);
        UUID otherOrg = fx.organization();
        fx.role(otherOrg, "CLIN_ANY", "encounter:read", "encounter:update", "encounter:update-any");
        AdminApiFixture.Session outsider = fx.loggedIn(otherOrg, "outsider", "CLIN_ANY");
        mockMvc.perform(outsider.apply(get(URL + "/" + id))).andExpect(status().isNotFound());
        putSection(outsider, id, "treatment-plan", "{\"version\":0,\"text\":\"x\"}").andExpect(status().isNotFound());
        mockMvc.perform(outsider.apply(get("/api/v1/patients/" + patient + "/encounters")))
                .andExpect(status().isNotFound());
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private static String obs(long version, String... items) {
        return "{\"version\":" + version + ",\"observations\":[" + String.join(",", items) + "]}";
    }

    private ResultActions start(AdminApiFixture.Session who, String body) throws Exception {
        return mockMvc.perform(who.apply(post(URL)).contentType(JSON).content(body));
    }

    private ResultActions putSection(AdminApiFixture.Session who, UUID id, String section, String body) throws Exception {
        return mockMvc.perform(who.apply(put(URL + "/" + id + "/" + section)).contentType(JSON).content(body));
    }

    private UUID startAdHoc(AdminApiFixture.Session who) throws Exception {
        String body = start(who, "{\"patientId\":\"" + patient + "\",\"chiefComplaint\":\"" + COMPLAINT + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String appointmentStatus(UUID appointment) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.appointment WHERE id = ?").param(appointment)
                .query(String.class).single());
    }

    private int encounterCount() {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.encounter").query(Integer.class).single());
    }

    private UUID insertAppointment(UUID practitioner, UUID who, String status) {
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
                .plus(15L * sequence++, ChronoUnit.MINUTES);
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(id, org, who, practitioner, Timestamp.from(start),
                        Timestamp.from(start.plus(15, ChronoUnit.MINUTES)), status, doctor.userId()).update());
        return id;
    }

    private UUID insertPatient(UUID organization, String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Encounter Patient', 'U', DATE '1990-01-01', '+910000000077', ?, now(), now())""")
                .params(id, organization, uhid, UUID.randomUUID()).update());
        return id;
    }

    private UUID insertPractitioner(UUID user) {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Test Medicine', now(), now())").params(specialty, org,
                    "G" + specialty.toString().substring(0, 8).toUpperCase()).update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name,"
                    + " registration_no, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Dr. Test Encounter', ?, ?, now(), now())")
                    .params(id, org, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user).update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(org, id, specialty).update();
        });
        return id;
    }
}
