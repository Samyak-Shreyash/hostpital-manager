package com.hms.clinical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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

/** [E6-3] Prescriptions: drafts, item rules (BR-C3), allergy warnings, issue, revise, immutability. Synthetic data. */
@SpringBootTest
@AutoConfigureMockMvc
class PrescriptionIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String ENCOUNTERS = "/api/v1/encounters";
    private static final String RX = "/api/v1/prescriptions";
    private static final String INSTRUCTIONS = "Take after food narwhal";

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
    private AdminApiFixture.Session doctor;
    private AdminApiFixture.Session otherDoctor;
    private AdminApiFixture.Session reader;
    private UUID patient;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "RXDOC", "encounter:create", "encounter:read", "encounter:update", "encounter:finalize",
                "prescription:create", "prescription:read", "prescription:issue");
        fx.role(org, "RXREAD", "prescription:read");
        String doctorEmail = fx.uniqueEmail("doctor");
        UUID doctorUser = fx.user(org, doctorEmail, "RXDOC");
        insertPractitioner(doctorUser);
        doctor = fx.login(doctorUser, doctorEmail);
        String otherEmail = fx.uniqueEmail("doctor");
        UUID otherUser = fx.user(org, otherEmail, "RXDOC");
        insertPractitioner(otherUser);
        otherDoctor = fx.login(otherUser, otherEmail);
        String readerEmail = fx.uniqueEmail("reader");
        reader = fx.login(fx.user(org, readerEmail, "RXREAD"), readerEmail);
        patient = insertPatient("HMS-000001");
    }

    @Test
    void draftIsAllowedOnOpenAndFinalizedEncountersOncePerEncounter() throws Exception {
        UUID open = startEncounter();
        rpost(doctor, ENCOUNTERS + "/" + open + "/prescriptions", "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("DRAFT"))).andExpect(jsonPath("$.versionNo", is(1)))
                .andExpect(jsonPath("$.encounterId", is(open.toString())))
                .andExpect(jsonPath("$.supersedesId", nullValue())).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.version", is(0)));
        rpost(doctor, ENCOUNTERS + "/" + open + "/prescriptions", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));

        UUID finalized = startEncounter();
        rpost(doctor, ENCOUNTERS + "/" + finalized + "/finalize", "{\"version\":0}").andExpect(status().isOk());
        rpost(doctor, ENCOUNTERS + "/" + finalized + "/prescriptions", "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void itemIdsAreUuidV7() throws Exception {
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 3) + ","
                + item("Ibuprofen", "1", "BD", 5) + "]}").andExpect(status().isOk());
        List<UUID> itemIds = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.prescription_item"
                + " WHERE prescription_id = ?").param(rx).query(UUID.class).list());
        assertThat(itemIds).hasSize(2).allSatisfy(id -> assertThat(id.version()).isEqualTo(7));
    }

    @Test
    void itemRulesAreEnforcedWithFieldErrors() throws Exception {
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", null, "TID", 3) + "]}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("items[0].dose")));
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", " ", 3) + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].frequency")));
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("", "1", "TID", 3) + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].drugName")));
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 0) + "]}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].durationDays")));
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 366) + "]}")
                .andExpect(status().isUnprocessableContent());
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[]}").andExpect(status().isUnprocessableContent());
        rput(doctor, RX + "/" + rx, "{\"items\":[" + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isUnprocessableContent());
        rget(doctor, RX + "/" + rx).andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void putReplacesItemsBumpsVersionAndChecksIt() throws Exception {
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].durationDays", is(3)))
                .andExpect(jsonPath("$.allergyWarnings", hasSize(0)));
        rput(doctor, RX + "/" + rx, "{\"version\":1,\"items\":[" + item("Ibuprofen", "1", "BD", 5) + ","
                + item("Cetirizine", "1", "OD", 7) + "]}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(2))).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].drugName", is("Ibuprofen")));
        rput(doctor, RX + "/" + rx, "{\"version\":1,\"items\":[" + item("Ibuprofen", "1", "BD", 5) + "]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
    }

    @Test
    void allergyWarningsMatchCaseInsensitivelyEitherWayOnPutAndGet() throws Exception {
        insertAllergy("Penicillin", "SEVERE", 0);
        insertAllergy("Sulfa", "MILD", 1);
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("PENICILLIN V potassium", "1", "QID", 5)
                + "," + item("sul", "1", "OD", 1) + "," + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.allergyWarnings", hasSize(2)))
                .andExpect(jsonPath("$.allergyWarnings[0].substance", is("Penicillin")))
                .andExpect(jsonPath("$.allergyWarnings[0].severity", is("SEVERE")))
                .andExpect(jsonPath("$.allergyWarnings[1].substance", is("Sulfa")));
        rget(doctor, RX + "/" + rx).andExpect(jsonPath("$.allergyWarnings", hasSize(2)));
        // An allergy entered in error no longer warns.
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.patient_clinical_entry SET status = 'ENTERED_IN_ERROR',"
                + " entered_in_error_by = ?, entered_in_error_at = now() WHERE subject = 'Sulfa'")
                .param(UUID.randomUUID()).update());
        rget(doctor, RX + "/" + rx).andExpect(jsonPath("$.allergyWarnings", hasSize(1)));
    }

    @Test
    void issueWithWarningsNeedsAcknowledgementAndAuditsNoClinicalText() throws Exception {
        insertAllergy("Penicillin", "SEVERE", 0);
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Penicillin V", "1", "QID", 5) + "]}")
                .andExpect(status().isOk());
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":1}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("ALLERGY_NOT_ACKNOWLEDGED")));
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":1,\"acknowledgeAllergyWarnings\":false}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("ALLERGY_NOT_ACKNOWLEDGED")));
        assertThat(rxStatus(rx)).isEqualTo("DRAFT");
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":1,\"acknowledgeAllergyWarnings\":true}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ISSUED")))
                .andExpect(jsonPath("$.issuedAt").isNotEmpty()).andExpect(jsonPath("$.version", is(2)))
                .andExpect(jsonPath("$.allergyWarnings", hasSize(1)));
        rget(doctor, RX + "/" + rx).andExpect(status().isOk());

        assertThat(fx.auditActions(rx)).containsExactly("PRESCRIPTION_CREATED", "PRESCRIPTION_ITEMS_UPDATED",
                "PRESCRIPTION_ISSUED", "PRESCRIPTION_VIEWED");
        List<String> rows = jdbc.sql("SELECT action || ' ' || array_to_string(changed_fields, ',')"
                + " FROM hms.audit_event WHERE entity_id = ?").param(rx).query(String.class).list();
        assertThat(String.join("|", rows)).doesNotContain("Penicillin").doesNotContain("narwhal")
                .doesNotContain("QID").doesNotContain("Paracetamol");
    }

    @Test
    void issueWithoutWarningsNeedsNoAcknowledgementButNeedsItems() throws Exception {
        UUID rx = newDraft();
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":0}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isOk());
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ISSUED")));
    }

    @Test
    void issuedPrescriptionCannotBeEditedOrIssuedAgainButReviseKeepsTheOld() throws Exception {
        UUID rx = issuedPrescription();
        rput(doctor, RX + "/" + rx, "{\"version\":2,\"items\":[" + item("Ibuprofen", "1", "BD", 5) + "]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));

        String body = rpost(doctor, RX + "/" + rx + "/revise", "{}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("DRAFT"))).andExpect(jsonPath("$.versionNo", is(2)))
                .andExpect(jsonPath("$.supersedesId", is(rx.toString()))).andExpect(jsonPath("$.items", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        UUID revised = UUID.fromString(JsonPath.read(body, "$.id"));
        rget(doctor, RX + "/" + rx).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("SUPERSEDED")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].drugName", is("Paracetamol")));
        // A second revise of the superseded one, or of a draft, is refused.
        rpost(doctor, RX + "/" + rx + "/revise", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        rpost(doctor, RX + "/" + revised + "/revise", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        // The draft revision is editable and issuable; then it is the only ISSUED one.
        rput(doctor, RX + "/" + revised, "{\"version\":0,\"items\":[" + item("Ibuprofen", "1", "BD", 5) + "]}")
                .andExpect(status().isOk());
        rpost(doctor, RX + "/" + revised + "/issue", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ISSUED")));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.prescription WHERE status = 'ISSUED'"
                + " AND encounter_id = (SELECT encounter_id FROM hms.prescription WHERE id = ?)").param(rx)
                .query(Long.class).single())).isEqualTo(1L);
    }

    @Test
    void onlyTheTreatingDoctorWritesAndPermissionsAreChecked() throws Exception {
        UUID rx = newDraft();
        UUID encounter = encounterOf(rx);
        rpost(otherDoctor, ENCOUNTERS + "/" + encounter + "/prescriptions", "{}").andExpect(status().isForbidden());
        rput(otherDoctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isForbidden());
        rpost(otherDoctor, RX + "/" + rx + "/issue", "{\"version\":0}").andExpect(status().isForbidden());
        rpost(otherDoctor, RX + "/" + rx + "/revise", "{}").andExpect(status().isForbidden());
        rpost(reader, ENCOUNTERS + "/" + encounter + "/prescriptions", "{}").andExpect(status().isForbidden());
        rpost(reader, RX + "/" + rx + "/issue", "{\"version\":0}").andExpect(status().isForbidden());
        // Any holder of prescription:read may read it.
        rget(reader, RX + "/" + rx).andExpect(status().isOk());
        rget(doctor, RX + "/" + UUID.randomUUID()).andExpect(status().isNotFound());
        rput(doctor, RX + "/" + UUID.randomUUID(), "{\"version\":0,\"items\":[]}").andExpect(status().isNotFound());
        rpost(doctor, ENCOUNTERS + "/" + UUID.randomUUID() + "/prescriptions", "{}")
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseRefusesChangesToAnIssuedPrescriptionExceptSupersede() throws Exception {
        UUID rx = issuedPrescription();
        assertThat(sqlState(rx, "UPDATE hms.prescription_item SET dose = '9' WHERE prescription_id = ?"))
                .isEqualTo("HM003");
        assertThat(sqlState(rx, "DELETE FROM hms.prescription_item WHERE prescription_id = ?")).isEqualTo("HM003");
        assertThat(sqlState(rx, "UPDATE hms.prescription SET status = 'DRAFT' WHERE id = ?")).isEqualTo("HM003");
        assertThat(sqlState(rx, "UPDATE hms.prescription SET issued_by = gen_random_uuid() WHERE id = ?"))
                .isEqualTo("HM003");
        assertThat(sqlState(rx, "DELETE FROM hms.prescription WHERE id = ?")).isEqualTo("HM003");
        assertThat(sqlState(rx, """
                INSERT INTO hms.prescription_item (id, organization_id, prescription_id, position, drug_name, dose,
                    frequency, duration_days) SELECT gen_random_uuid(), organization_id, id, 9, 'X', '1', 'OD', 1
                FROM hms.prescription WHERE id = ?""")).isEqualTo("HM003");
        assertThat(sqlState(rx, "UPDATE hms.prescription SET status = 'SUPERSEDED' WHERE id = ?")).isNull();
        assertThat(sqlState(rx, "UPDATE hms.prescription SET status = 'ISSUED' WHERE id = ?")).isEqualTo("HM003");
    }

    @Test
    void pdfOfAnIssuedPrescriptionHasBusinessDataNoUuidAndIsAudited() throws Exception {
        String orgName = fx.inOrg(org, () -> {
            jdbc.sql("UPDATE hms.organization SET address = '7 Synthetic Lane, Testville' WHERE id = ?")
                    .param(org).update();
            return jdbc.sql("SELECT name FROM hms.organization WHERE id = ?").param(org).query(String.class)
                    .single();
        });
        UUID rx = issuedPrescription();
        // Every test inserts its own 'Dr. Test Rx', so take the doctor of this prescription's encounter.
        String regNo = fx.inOrg(org, () -> jdbc.sql("SELECT p.registration_no FROM hms.prescription r"
                + " JOIN hms.encounter e ON e.id = r.encounter_id JOIN hms.practitioner p ON p.id = e.practitioner_id"
                + " WHERE r.id = ?").param(rx).query(String.class).single());
        byte[] pdf = rget(reader, RX + "/" + rx + "/pdf").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        String text = pdfText(pdf);
        assertThat(text).contains(orgName, "7 Synthetic Lane, Testville", "Test Rx Patient", "HMS-000001",
                "Dr. Test Rx", regNo, "Paracetamol", "500 mg", "TID", INSTRUCTIONS);
        assertThat(text).doesNotContain("SUPERSEDED");
        assertThat(text).doesNotContainPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                + "[0-9a-fA-F]{12}");

        assertThat(fx.auditActions(rx)).contains("PRESCRIPTION_PDF_GENERATED");
        List<String> rows = jdbc.sql("SELECT action || ' ' || array_to_string(changed_fields, ',')"
                + " FROM hms.audit_event WHERE entity_id = ? AND action = 'PRESCRIPTION_PDF_GENERATED'")
                .param(rx).query(String.class).list();
        assertThat(rows).hasSize(1);
        assertThat(String.join("|", rows)).doesNotContain("Paracetamol").doesNotContain("narwhal");
    }

    @Test
    void pdfOfASupersededPrescriptionIsWatermarkedAndADraftHasNoPdf() throws Exception {
        UUID rx = issuedPrescription();
        UUID revised = UUID.fromString(JsonPath.read(rpost(doctor, RX + "/" + rx + "/revise", "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id"));
        byte[] pdf = rget(doctor, RX + "/" + rx + "/pdf").andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsByteArray();
        assertThat(pdfText(pdf)).contains("SUPERSEDED", "Paracetamol");
        rget(doctor, RX + "/" + revised + "/pdf").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        rget(doctor, RX + "/" + UUID.randomUUID() + "/pdf").andExpect(status().isNotFound());
    }

    private static String pdfText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        try {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder all = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                all.append(extractor.getTextFromPage(page)).append(' ');
            }
            return all.toString();
        } finally {
            reader.close();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------

    private UUID issuedPrescription() throws Exception {
        UUID rx = newDraft();
        rput(doctor, RX + "/" + rx, "{\"version\":0,\"items\":[" + item("Paracetamol", "1", "TID", 3) + "]}")
                .andExpect(status().isOk());
        rpost(doctor, RX + "/" + rx + "/issue", "{\"version\":1}").andExpect(status().isOk());
        return rx;
    }

    private static String item(String drug, String dose, String frequency, int days) {
        return "{\"drugName\":\"" + drug + "\",\"strength\":\"500 mg\",\"form\":\"Tablet\",\"route\":\"Oral\","
                + (dose == null ? "" : "\"dose\":\"" + dose + "\",") + "\"frequency\":\"" + frequency
                + "\",\"durationDays\":" + days + ",\"instructions\":\"" + INSTRUCTIONS + "\"}";
    }

    private UUID newDraft() throws Exception {
        UUID encounter = startEncounter();
        String body = rpost(doctor, ENCOUNTERS + "/" + encounter + "/prescriptions", "{}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID startEncounter() throws Exception {
        String body = mockMvc.perform(doctor.apply(post(ENCOUNTERS)).contentType(JSON)
                .content("{\"patientId\":\"" + patient + "\"}")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID encounterOf(UUID rx) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT encounter_id FROM hms.prescription WHERE id = ?").param(rx)
                .query(UUID.class).single());
    }

    private String rxStatus(UUID rx) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.prescription WHERE id = ?").param(rx)
                .query(String.class).single());
    }

    private ResultActions rpost(AdminApiFixture.Session who, String url, String body) throws Exception {
        return mockMvc.perform(who.apply(post(url)).contentType(JSON).content(body));
    }

    private ResultActions rput(AdminApiFixture.Session who, String url, String body) throws Exception {
        return mockMvc.perform(who.apply(put(url)).contentType(JSON).content(body));
    }

    private ResultActions rget(AdminApiFixture.Session who, String url) throws Exception {
        return mockMvc.perform(who.apply(get(url)));
    }

    /** One statement on a direct hms_owner connection in the org context; returns its SQLState or null. */
    private String sqlState(UUID rx, String sql) throws java.sql.SQLException {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(PostgresIT.postgres.getJdbcUrl(),
                PostgresIT.OWNER, PostgresIT.OWNER_PASSWORD)) {
            try (java.sql.PreparedStatement context = connection.prepareStatement(
                    "SELECT set_config('app.organization_id', ?, false)")) {
                context.setString(1, org.toString());
                context.execute();
            }
            try (java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, rx);
                statement.executeUpdate();
                return null;
            }
        } catch (java.sql.SQLException e) {
            return e.getSQLState();
        }
    }

    private void insertAllergy(String substance, String severity, int secondsOffset) {
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient_clinical_entry (id, organization_id, patient_id, kind, subject, severity,
                    status, recorded_by, created_at)
                VALUES (?, ?, ?, 'ALLERGY', ?, ?, 'ACTIVE', ?, now() + (? * interval '1 second'))""")
                .params(UUID.randomUUID(), org, patient, substance, severity, UUID.randomUUID(), secondsOffset)
                .update());
    }

    private UUID insertPatient(String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Rx Patient', 'U', DATE '1990-01-01', '+910000000078', ?, now(), now())""")
                .params(id, org, uhid, UUID.randomUUID()).update());
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
                    + " VALUES (?, ?, ?, 'Dr. Test Rx', ?, ?, now(), now())")
                    .params(id, org, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user).update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(org, id, specialty).update();
        });
        return id;
    }
}
