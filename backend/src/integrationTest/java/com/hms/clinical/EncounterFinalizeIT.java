package com.hms.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.clinical.api.EncounterFinalized;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/** [E6-2] Finalize, addenda and the database guard on finalized encounters. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class EncounterFinalizeIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/encounters";
    private static final String ADDENDUM_TEXT = "Synthetic addendum narwhal";

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
    ApplicationEvents events;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session doctor;
    private UUID doctorId;
    private AdminApiFixture.Session otherDoctor;
    private AdminApiFixture.Session noFinalize;
    private UUID patient;
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "CLIN", "encounter:create", "encounter:read", "encounter:update", "observation:write",
                "encounter:finalize", "encounter:addendum");
        fx.role(org, "CLIN_PLAIN", "encounter:create", "encounter:read", "encounter:update", "observation:write");
        String doctorEmail = fx.uniqueEmail("doctor");
        UUID doctorUser = fx.user(org, doctorEmail, "CLIN");
        doctorId = insertPractitioner(doctorUser);
        doctor = fx.login(doctorUser, doctorEmail);
        String otherEmail = fx.uniqueEmail("doctor");
        UUID otherUser = fx.user(org, otherEmail, "CLIN");
        insertPractitioner(otherUser);
        otherDoctor = fx.login(otherUser, otherEmail);
        String plainEmail = fx.uniqueEmail("plain");
        UUID plainUser = fx.user(org, plainEmail, "CLIN_PLAIN");
        insertPractitioner(plainUser);
        noFinalize = fx.login(plainUser, plainEmail);
        patient = insertPatient(org, "HMS-000001");
    }

    @Test
    void finalizingMarksTheAppointmentCompletedAuditsAndPublishes() throws Exception {
        UUID appointment = insertAppointment(doctorId, patient, "CHECKED_IN");
        String body = mockMvc.perform(doctor.apply(post(URL)).contentType(JSON)
                .content("{\"appointmentId\":\"" + appointment + "\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        putSection(doctor, id, "treatment-plan", "{\"version\":0,\"text\":\"Synthetic plan\"}")
                .andExpect(status().isOk());

        finalize(doctor, id, 1).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("FINALIZED")))
                .andExpect(jsonPath("$.version", is(2))).andExpect(jsonPath("$.finalizedAt").isNotEmpty());

        assertThat(appointmentStatus(appointment)).isEqualTo("COMPLETED");
        assertThat(fx.auditActions(id)).containsExactly("ENCOUNTER_STARTED", "ENCOUNTER_TREATMENT_PLAN_UPDATED",
                "ENCOUNTER_FINALIZED");
        List<EncounterFinalized> published = events.stream(EncounterFinalized.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).encounterId()).isEqualTo(id);
        assertThat(published.get(0).patientId()).isEqualTo(patient);
        assertThat(published.get(0).appointmentId()).isEqualTo(appointment);
        assertThat(published.get(0).organizationId()).isEqualTo(org);
    }

    @Test
    void anAdHocEncounterFinalizesWithoutAnAppointment() throws Exception {
        UUID id = startAdHoc(doctor);
        finalize(doctor, id, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("FINALIZED")));
        assertThat(events.stream(EncounterFinalized.class).toList()).hasSize(1);
    }

    @Test
    void finalizeNeedsTheTreatingDoctorTheRightVersionAndAnOpenEncounter() throws Exception {
        UUID id = startAdHoc(doctor);
        finalize(otherDoctor, id, 0).andExpect(status().isForbidden());
        finalize(noFinalize, id, 0).andExpect(status().isForbidden());
        finalize(doctor, id, 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(doctor.apply(post(URL + "/" + id + "/finalize")).contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(encounterStatus(id)).isEqualTo("OPEN");
        finalize(doctor, id, 0).andExpect(status().isOk());
        finalize(doctor, id, 1).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        finalize(doctor, UUID.randomUUID(), 0).andExpect(status().isNotFound());
    }

    @Test
    void everyEditOnAFinalizedEncounterIsRefused() throws Exception {
        UUID id = startAdHoc(doctor);
        finalize(doctor, id, 0).andExpect(status().isOk());
        putSection(doctor, id, "observations", "{\"version\":1,\"observations\":[]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":1,\"text\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        putSection(doctor, id, "diagnoses", "{\"version\":1,\"diagnoses\":[]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        putSection(doctor, id, "treatment-plan", "{\"version\":1,\"text\":\"x\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
        putSection(doctor, id, "follow-up", "{\"version\":1,\"date\":\"2030-01-15\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("ENCOUNTER_FINALIZED")));
    }

    @Test
    void addendaOnlyAfterFinalizeAndNeverAuditTheText() throws Exception {
        UUID id = startAdHoc(doctor);
        addendum(doctor, id, ADDENDUM_TEXT).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        finalize(doctor, id, 0).andExpect(status().isOk());

        addendum(doctor, id, ADDENDUM_TEXT).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("FINALIZED")))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.notes[0].kind", is("ADDENDUM")))
                .andExpect(jsonPath("$.notes[0].text", is(ADDENDUM_TEXT)));
        addendum(doctor, id, "Second synthetic addendum").andExpect(status().isCreated())
                .andExpect(jsonPath("$.notes.length()", is(2)));
        addendum(doctor, id, "  ").andExpect(status().isUnprocessableContent());
        addendum(otherDoctor, id, ADDENDUM_TEXT).andExpect(status().isForbidden());
        addendum(noFinalize, id, ADDENDUM_TEXT).andExpect(status().isForbidden());
        addendum(doctor, UUID.randomUUID(), ADDENDUM_TEXT).andExpect(status().isNotFound());

        List<String> rows = jdbc.sql("SELECT action || ' ' || array_to_string(changed_fields, ',')"
                + " FROM hms.audit_event WHERE entity_id = ?").param(id).query(String.class).list();
        assertThat(fx.auditActions(id)).containsExactly("ENCOUNTER_STARTED", "ENCOUNTER_FINALIZED",
                "ENCOUNTER_ADDENDUM_ADDED", "ENCOUNTER_ADDENDUM_ADDED");
        assertThat(String.join("|", rows)).doesNotContain("narwhal").doesNotContain("Second synthetic");
    }

    @Test
    void directSqlUpdateOnFinalizedEncounterIsRejected() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "observations",
                "{\"version\":0,\"observations\":[{\"kind\":\"PULSE\",\"value\":\"72\",\"unit\":\"bpm\"}]}")
                .andExpect(status().isOk());
        putSection(doctor, id, "notes/SYMPTOMS", "{\"version\":1,\"text\":\"Synthetic symptoms\"}")
                .andExpect(status().isOk());
        putSection(doctor, id, "diagnoses",
                "{\"version\":2,\"diagnoses\":[{\"description\":\"Synthetic dx\",\"primary\":true}]}")
                .andExpect(status().isOk());
        putSection(doctor, id, "treatment-plan", "{\"version\":3,\"text\":\"Synthetic plan\"}")
                .andExpect(status().isOk());
        putSection(doctor, id, "follow-up", "{\"version\":4,\"date\":\"2030-01-15\"}").andExpect(status().isOk());

        // While OPEN the database lets the owner change children (the service is the gate there).
        assertThat(sqlState(id, "UPDATE hms.encounter_observation SET unit = 'bpm' WHERE encounter_id = ?")).isNull();

        finalize(doctor, id, 5).andExpect(status().isOk());

        String[] statements = {
                "UPDATE hms.encounter_observation SET value = 99 WHERE encounter_id = ?",
                "UPDATE hms.encounter_observation SET replaced_at = now() WHERE encounter_id = ?",
                "DELETE FROM hms.encounter_observation WHERE encounter_id = ?",
                "UPDATE hms.clinical_note SET text = 'tampered' WHERE encounter_id = ?",
                "UPDATE hms.clinical_note SET replaced_at = now() WHERE encounter_id = ?",
                "UPDATE hms.encounter_diagnosis SET description = 'tampered' WHERE encounter_id = ?",
                "UPDATE hms.encounter_diagnosis SET replaced_at = now() WHERE encounter_id = ?",
                "UPDATE hms.treatment_plan SET text = 'tampered' WHERE encounter_id = ?",
                "DELETE FROM hms.treatment_plan WHERE encounter_id = ?",
                "UPDATE hms.follow_up SET instructions = 'tampered' WHERE encounter_id = ?",
                "UPDATE hms.encounter SET chief_complaint = 'tampered' WHERE id = ?",
                "UPDATE hms.encounter SET status = 'OPEN', finalized_at = NULL, finalized_by = NULL WHERE id = ?",
                "DELETE FROM hms.encounter WHERE id = ?"};
        for (String sql : statements) {
            assertThat(sqlState(id, sql)).as(sql).isEqualTo("HM005");
        }
        // INSERTs: a new vital or non-addendum note on a finalized encounter is refused.
        assertThat(sqlState(id, insertNote("SYMPTOMS"))).isEqualTo("HM005");
        assertThat(sqlState(id, """
                INSERT INTO hms.encounter_observation (id, organization_id, encounter_id, kind, value, unit,
                    recorded_by, created_at) SELECT gen_random_uuid(), organization_id, id, 'SPO2', 98, '%',
                    created_by, now() FROM hms.encounter WHERE id = ?""")).isEqualTo("HM005");
        // An ADDENDUM may still be inserted, but never changed or removed.
        assertThat(sqlState(id, insertNote("ADDENDUM"))).isNull();
        assertThat(sqlState(id,
                "UPDATE hms.clinical_note SET text = 'tampered' WHERE encounter_id = ? AND kind = 'ADDENDUM'"))
                .isEqualTo("HM005");
        assertThat(sqlState(id, "DELETE FROM hms.clinical_note WHERE encounter_id = ? AND kind = 'ADDENDUM'"))
                .isEqualTo("HM005");
    }

    @Test
    void observationAndDiagnosisIdsAreUuidV7() throws Exception {
        UUID id = startAdHoc(doctor);
        putSection(doctor, id, "observations",
                "{\"version\":0,\"observations\":[{\"kind\":\"PULSE\",\"value\":\"72\",\"unit\":\"bpm\"}]}")
                .andExpect(status().isOk());
        putSection(doctor, id, "diagnoses",
                "{\"version\":1,\"diagnoses\":[{\"description\":\"Synthetic dx\",\"primary\":true}]}")
                .andExpect(status().isOk());
        java.util.List<UUID> rowIds = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.encounter_observation"
                + " WHERE encounter_id = ? UNION ALL SELECT id FROM hms.encounter_diagnosis WHERE encounter_id = ?")
                .params(id, id).query(UUID.class).list());
        assertThat(rowIds).hasSize(2).allSatisfy(rowId -> assertThat(rowId.version()).isEqualTo(7));
    }

    @Test
    void childGuardFailsClosedWhenTheParentEncounterIsNotFound() throws Exception {
        // V35: a child row whose parent encounter is missing (or RLS-invisible) is refused, even an ADDENDUM.
        UUID id = startAdHoc(doctor);
        assertThat(sqlState(id, """
                INSERT INTO hms.encounter_observation (id, organization_id, encounter_id, kind, value, unit,
                    recorded_by, created_at) SELECT gen_random_uuid(), organization_id, gen_random_uuid(), 'SPO2',
                    98, '%', created_by, now() FROM hms.encounter WHERE id = ?""")).isEqualTo("HM005");
        assertThat(sqlState(id, """
                INSERT INTO hms.clinical_note (id, organization_id, encounter_id, kind, text, author_id, created_at)
                SELECT gen_random_uuid(), organization_id, gen_random_uuid(), 'ADDENDUM', 'Synthetic direct',
                    created_by, now() FROM hms.encounter WHERE id = ?""")).isEqualTo("HM005");
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    /**
     * Runs one statement on a direct hms_owner connection (the migration role, which RLS also binds) in the org
     * context; returns its SQLState, or null when it succeeds.
     */
    private String sqlState(UUID encounterId, String sql) throws java.sql.SQLException {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(PostgresIT.postgres.getJdbcUrl(),
                PostgresIT.OWNER, PostgresIT.OWNER_PASSWORD)) {
            try (java.sql.PreparedStatement context = connection.prepareStatement(
                    "SELECT set_config('app.organization_id', ?, false)")) {
                context.setString(1, org.toString());
                context.execute();
            }
            try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, encounterId);
                statement.executeUpdate();
                return null;
            }
        } catch (java.sql.SQLException e) {
            return e.getSQLState();
        }
    }

    private static String insertNote(String kind) {
        return "INSERT INTO hms.clinical_note (id, organization_id, encounter_id, kind, text, author_id, created_at)"
                + " SELECT gen_random_uuid(), organization_id, id, '" + kind
                + "', 'Synthetic direct', created_by, now() FROM hms.encounter WHERE id = ?";
    }

    private ResultActions finalize(AdminApiFixture.Session who, UUID id, long version) throws Exception {
        return mockMvc.perform(who.apply(post(URL + "/" + id + "/finalize")).contentType(JSON)
                .content("{\"version\":" + version + "}"));
    }

    private ResultActions addendum(AdminApiFixture.Session who, UUID id, String text) throws Exception {
        return mockMvc.perform(who.apply(post(URL + "/" + id + "/addenda")).contentType(JSON)
                .content("{\"text\":\"" + text + "\"}"));
    }

    private String encounterStatus(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.encounter WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private ResultActions putSection(AdminApiFixture.Session who, UUID id, String section, String body)
            throws Exception {
        return mockMvc.perform(who.apply(put(URL + "/" + id + "/" + section)).contentType(JSON).content(body));
    }

    private UUID startAdHoc(AdminApiFixture.Session who) throws Exception {
        String body = mockMvc.perform(who.apply(post(URL)).contentType(JSON)
                .content("{\"patientId\":\"" + patient + "\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String appointmentStatus(UUID appointment) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.appointment WHERE id = ?").param(appointment)
                .query(String.class).single());
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
