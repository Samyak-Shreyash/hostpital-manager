package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
 * [E3-4] Allergies and medical history: append-only, entered-in-error as a one-way status change, no delete,
 * audit by field name and entry id only. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatientClinicalEntryIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String SUBSTANCE = "Syntheticillin";
    private static final String REACTION = "Synthetic rash on both arms";
    private static final String CONDITION = "Syntheticosis";
    private static final String NOTES = "Synthetic note about the condition";

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
    private AdminApiFixture.Session clinician;
    private AdminApiFixture.Session readerOnly;
    private UUID patient;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        AdminApiFixture.Session reception = fx.loggedIn(org, "reception", "RECEPTIONIST");
        fx.role(org, "CLINICIAN", "patient:read", "patient:update-clinical");
        clinician = fx.loggedIn(org, "clinician", "CLINICIAN");
        fx.role(org, "READER", "patient:read");
        readerOnly = fx.loggedIn(org, "reader", "READER");
        patient = register(reception);
    }

    private UUID register(AdminApiFixture.Session s) throws Exception {
        String response = mockMvc.perform(s.apply(post(URL)).contentType(JSON).content(
                "{\"fullName\":\"Meera Iyer\",\"sex\":\"F\",\"dateOfBirth\":\"1985-03-02\",\"phone\":"
                        + "\"+910400000001\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions addAllergy(AdminApiFixture.Session s, UUID patientId, String json) throws Exception {
        return mockMvc.perform(s.apply(post(URL + "/{id}/allergies", patientId)).contentType(JSON).content(json));
    }

    private UUID newAllergy() throws Exception {
        String body = addAllergy(clinician, patient, "{\"substance\":\"" + SUBSTANCE + "\",\"reaction\":\""
                + REACTION + "\",\"severity\":\"SEVERE\"}").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID newHistory() throws Exception {
        String body = mockMvc.perform(clinician.apply(post(URL + "/{id}/medical-history", patient))
                .contentType(JSON).content("{\"condition\":\"" + CONDITION + "\",\"notes\":\"" + NOTES + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions markAllergy(AdminApiFixture.Session s, UUID patientId, UUID entryId) throws Exception {
        return mockMvc.perform(s.apply(patch(URL + "/{id}/allergies/{e}", patientId, entryId)).contentType(JSON)
                .content("{\"status\":\"ENTERED_IN_ERROR\"}"));
    }

    @Test
    void allergyIsAddedListedAndAuditedByFieldNameAndEntryId() throws Exception {
        UUID entry = newAllergy();
        mockMvc.perform(readerOnly.apply(get(URL + "/{id}/allergies", patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(entry.toString())))
                .andExpect(jsonPath("$[0].substance", is(SUBSTANCE)))
                .andExpect(jsonPath("$[0].reaction", is(REACTION)))
                .andExpect(jsonPath("$[0].severity", is("SEVERE")))
                .andExpect(jsonPath("$[0].status", is("ACTIVE")))
                .andExpect(jsonPath("$[0].enteredInErrorAt", nullValue()));

        assertThat(fx.auditActions(entry)).containsExactly("PATIENT_ALLERGY_ADDED");
        assertThat(fx.auditActions(patient)).contains("PATIENT_ALLERGIES_VIEWED");
        for (String row : jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a").query(String.class).list()) {
            assertThat(row).doesNotContain(SUBSTANCE).doesNotContain(REACTION);
        }
    }

    @Test
    void medicalHistoryIsAddedListedAndAudited() throws Exception {
        UUID entry = newHistory();
        mockMvc.perform(readerOnly.apply(get(URL + "/{id}/medical-history", patient)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].condition", is(CONDITION)))
                .andExpect(jsonPath("$[0].notes", is(NOTES)))
                .andExpect(jsonPath("$[0].status", is("ACTIVE")));
        assertThat(fx.auditActions(entry)).containsExactly("PATIENT_HISTORY_ADDED");
        for (String row : jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a").query(String.class).list()) {
            assertThat(row).doesNotContain(CONDITION).doesNotContain(NOTES);
        }
    }

    @Test
    void enteredInErrorKeepsTheEntryVisibleAndSecondAttemptIsAConflict() throws Exception {
        UUID entry = newAllergy();
        markAllergy(clinician, patient, entry).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ENTERED_IN_ERROR")))
                .andExpect(jsonPath("$.substance", is(SUBSTANCE)));
        markAllergy(clinician, patient, entry).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ENTRY_ALREADY_ENTERED_IN_ERROR")));
        mockMvc.perform(readerOnly.apply(get(URL + "/{id}/allergies", patient)))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].status", is("ENTERED_IN_ERROR")));
        assertThat(fx.auditActions(entry)).containsExactly("PATIENT_ALLERGY_ADDED",
                "PATIENT_ALLERGY_MARKED_IN_ERROR");

        UUID history = newHistory();
        mockMvc.perform(clinician.apply(patch(URL + "/{id}/medical-history/{e}", patient, history))
                .contentType(JSON).content("{\"status\":\"ENTERED_IN_ERROR\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ENTERED_IN_ERROR")));
    }

    @Test
    void onlyEnteredInErrorIsAcceptedAsStatusAndInputIsValidated() throws Exception {
        UUID entry = newAllergy();
        mockMvc.perform(clinician.apply(patch(URL + "/{id}/allergies/{e}", patient, entry)).contentType(JSON)
                .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        addAllergy(clinician, patient, "{\"substance\":\"X\",\"severity\":\"EXTREME\"}")
                .andExpect(status().isUnprocessableContent());
        addAllergy(clinician, patient, "{\"substance\":\"  \",\"severity\":\"MILD\"}")
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void permissionsAndOrganizationIsolation() throws Exception {
        UUID entry = newAllergy();
        addAllergy(readerOnly, patient, "{\"substance\":\"X\",\"severity\":\"MILD\"}")
                .andExpect(status().isForbidden());
        markAllergy(readerOnly, patient, entry).andExpect(status().isForbidden());
        fx.role(org, "NOREAD", "patient:search");
        mockMvc.perform(fx.loggedIn(org, "noread", "NOREAD").apply(get(URL + "/{id}/allergies", patient)))
                .andExpect(status().isForbidden());

        UUID other = fx.organization();
        AdminApiFixture.Session otherClinician = fx.loggedIn(other, "other", "RECEPTIONIST");
        // Another organization's patient id and entry id are 404, for read, add and mark.
        mockMvc.perform(otherClinician.apply(get(URL + "/{id}/allergies", patient))).andExpect(status().isNotFound());
        addAllergy(otherClinician, patient, "{\"substance\":\"X\",\"severity\":\"MILD\"}")
                .andExpect(status().isNotFound());
        markAllergy(otherClinician, patient, entry).andExpect(status().isNotFound());
        UUID otherPatient = register(otherClinician);
        markAllergy(otherClinician, otherPatient, entry).andExpect(status().isNotFound());
        // An allergy id under the medical-history path is not found either.
        mockMvc.perform(clinician.apply(patch(URL + "/{id}/medical-history/{e}", patient, entry)).contentType(JSON)
                .content("{\"status\":\"ENTERED_IN_ERROR\"}")).andExpect(status().isNotFound());
        markAllergy(clinician, patient, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.patient_clinical_entry WHERE id = ?")
                .param(entry).query(String.class).single())).isEqualTo("ACTIVE");
    }

    @Test
    void contentCannotBeChangedAndRowsCannotBeDeletedEvenWithAnEntryInError() throws Exception {
        UUID entry = newAllergy();
        // Application role: no table-level UPDATE, only the three status columns.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.patient_clinical_entry SET subject = 'Changed' WHERE id = ?").param(entry).update()))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "DELETE FROM hms.patient_clinical_entry WHERE id = ?").param(entry).update()))
                .rootCause().hasMessageContaining("permission denied");
        // The allowed columns are still guarded by the trigger: only ACTIVE -> ENTERED_IN_ERROR.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.patient_clinical_entry SET status = 'ACTIVE' WHERE id = ?").param(entry).update()))
                .rootCause().hasMessageContaining("may only change from ACTIVE to ENTERED_IN_ERROR");
        markAllergy(clinician, patient, entry).andExpect(status().isOk());
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.patient_clinical_entry SET status = 'ACTIVE', entered_in_error_by = NULL, "
                        + "entered_in_error_at = NULL WHERE id = ?").param(entry).update()))
                .rootCause().hasMessageContaining("may only change from ACTIVE to ENTERED_IN_ERROR");
    }

    @Test
    void theTriggerAlsoProtectsAgainstTheOwnerRole() throws Exception {
        UUID entry = newAllergy();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(postgres.getJdbcUrl(), OWNER,
                OWNER_PASSWORD); java.sql.Statement st = c.createStatement()) {
            st.execute("SELECT set_config('app.organization_id', '" + org + "', false)");
            assertThatThrownBy(() -> st.execute("UPDATE hms.patient_clinical_entry SET subject = 'Changed', "
                    + "status = 'ENTERED_IN_ERROR', entered_in_error_by = recorded_by, entered_in_error_at = now() "
                    + "WHERE id = '" + entry + "'")).hasMessageContaining("content is immutable");
            assertThatThrownBy(() -> st.execute("DELETE FROM hms.patient_clinical_entry WHERE id = '" + entry + "'"))
                    .hasMessageContaining("cannot be deleted");
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT subject FROM hms.patient_clinical_entry WHERE id = ?")
                .param(entry).query(String.class).single())).isEqualTo(SUBSTANCE);
    }
}
