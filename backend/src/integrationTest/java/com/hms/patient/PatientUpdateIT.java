package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E3-5] Patient update: every field but the UHID, identity fields behind patient:update-identity plus a reason,
 * optimistic locking, audit by field name, duplicate re-check. Synthetic data only (+9103... phones, invented names).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatientUpdateIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String REASON = "Synthetic: spelling corrected from ID card";
    private static final String ABHA = "00123456789012";

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
    private AdminApiFixture.Session contactOnly;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        // Front desk plus identity edits: the built-in RECEPTIONIST no longer holds patient:update-identity
        // (owner decision 2026-11-14), so the identity tests use a custom role that does.
        fx.role(org, "RECORDS_OFFICER", "patient:read", "patient:search", "patient:create", "patient:update",
                "patient:update-identity", "patient:override-duplicate");
        reception = fx.loggedIn(org, "reception", "RECORDS_OFFICER");
        fx.role(org, "CONTACT_EDITOR", "patient:read", "patient:update");
        contactOnly = fx.loggedIn(org, "contacts", "CONTACT_EDITOR");
    }

    @Test
    void theBuiltInReceptionistCannotEditIdentityFields() throws Exception {
        UUID id = registered("Meera Iyer", "1990-01-01", "+910300000090");
        AdminApiFixture.Session frontDesk = fx.loggedIn(org, "frontdesk", "RECEPTIONIST");
        update(frontDesk, id, "{\"version\":0,\"fullName\":\"Meera Iyengar\",\"identityChangeReason\":\""
                + REASON + "\"}").andExpect(status().isForbidden());
        update(frontDesk, id, "{\"version\":0,\"phone\":\"+910300000091\"}").andExpect(status().isOk());
    }

    private UUID registered(String name, String dob, String phone) throws Exception {
        String response = mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(
                "{\"fullName\":\"" + name + "\",\"sex\":\"F\",\"dateOfBirth\":\"" + dob + "\",\"phone\":\"" + phone
                        + "\",\"address\":{\"line1\":\"12 Synthetic Lane\",\"city\":\"Testpur\",\"state\":\"Demo\","
                        + "\"pincode\":\"400001\"}}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private UUID registered() throws Exception {
        return registered("Meera Iyer", "1985-03-02", "+910300000001");
    }

    private ResultActions update(AdminApiFixture.Session s, UUID id, String json) throws Exception {
        return mockMvc.perform(s.apply(patch(URL + "/{id}", id)).contentType(JSON).content(json));
    }

    private List<String> auditRows(UUID id) {
        return jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?").param(id)
                .query(String.class).list();
    }

    private String changedFields(UUID id, String action) {
        return jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? AND action = ?")
                .params(id, action).query(String.class).single();
    }

    @Test
    void contactFieldsNeedOnlyPatientUpdateAndAreAuditedByFieldName() throws Exception {
        UUID id = registered();
        update(contactOnly, id, "{\"version\":0,\"phone\":\"0300000042\",\"address\":{\"line1\":\"7 Mock Road\","
                + "\"city\":\"Testpur\",\"state\":\"Demo\",\"pincode\":\"400002\"},\"emergencyContact\":{\"name\":"
                + "\"Ravi Iyer\",\"relationship\":\"Brother\",\"phone\":\"+910300000043\"}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone", is("+910300000042")))
                .andExpect(jsonPath("$.address.line1", is("7 Mock Road")))
                .andExpect(jsonPath("$.address.pincode", is("400002")))
                .andExpect(jsonPath("$.emergencyContact.name", is("Ravi Iyer")))
                .andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.uhid", is("HMS-000001")));

        assertThat(fx.auditActions(id)).containsExactly("PATIENT_CREATED", "PATIENT_UPDATED");
        assertThat(changedFields(id, "PATIENT_UPDATED")).contains("phone", "address.line1", "address.pincode",
                "emergencyContact.name", "emergencyContact.relationship", "emergencyContact.phone")
                .doesNotContain("address.city").doesNotContain("fullName");
        for (String row : auditRows(id)) {
            assertThat(row).doesNotContain("0300000042").doesNotContain("Mock Road").doesNotContain("Ravi");
        }
        // The old address is kept as history; exactly one is current.
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FILTER (WHERE is_current) || '/' || count(*) "
                + "FROM hms.patient_address WHERE patient_id = ?").param(id).query(String.class).single()))
                .isEqualTo("1/2");
    }

    @Test
    void identityFieldsNeedThePermissionAndAReasonWhichStaysOutOfAudit() throws Exception {
        UUID id = registered();
        update(contactOnly, id, "{\"version\":0,\"fullName\":\"Meera Iyengar\",\"identityChangeReason\":\""
                + REASON + "\"}").andExpect(status().isForbidden());
        update(contactOnly, id, "{\"version\":0,\"sex\":\"O\",\"identityChangeReason\":\"" + REASON + "\"}")
                .andExpect(status().isForbidden());
        update(contactOnly, id, "{\"version\":0,\"dateOfBirth\":\"1985-03-03\",\"identityChangeReason\":\""
                + REASON + "\"}").andExpect(status().isForbidden());
        // Resending the unchanged name is not an identity change.
        update(contactOnly, id, "{\"version\":0,\"fullName\":\"Meera Iyer\",\"phone\":\"+910300000044\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));

        update(reception, id, "{\"version\":1,\"fullName\":\"Meera Iyengar\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        update(reception, id, "{\"version\":1,\"fullName\":\"Meera Iyengar\",\"sex\":\"O\",\"dateOfBirth\":"
                + "\"1985-03-03\",\"identityChangeReason\":\"" + REASON + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName", is("Meera Iyengar")))
                .andExpect(jsonPath("$.sex", is("O")))
                .andExpect(jsonPath("$.dateOfBirth", is("1985-03-03")))
                .andExpect(jsonPath("$.version", is(2)));

        var row = fx.inOrg(org, () -> jdbc.sql("SELECT reason, changed_fields::text AS f, changed_by FROM "
                + "hms.patient_identity_change WHERE patient_id = ?").param(id).query((rs, n) -> List.of(
                rs.getString("reason"), rs.getString("f"), rs.getObject("changed_by", UUID.class).toString()))
                .single());
        assertThat(row.get(0)).isEqualTo(REASON);
        assertThat(row.get(1)).contains("fullName", "sex", "dateOfBirth");
        assertThat(row.get(2)).isEqualTo(reception.userId().toString());
        assertThat(changedFields(id, "PATIENT_IDENTITY_CHANGED")).contains("fullName", "identityChange.reason");
        for (String audit : auditRows(id)) {
            assertThat(audit).doesNotContain("ID card").doesNotContain("Iyengar").doesNotContain("1985-03-03");
        }
    }

    @Test
    void identityChangeRowsCannotBeChangedByTheApplicationRole() throws Exception {
        UUID id = registered();
        update(reception, id, "{\"version\":0,\"sex\":\"O\",\"identityChangeReason\":\"" + REASON + "\"}")
                .andExpect(status().isOk());
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.patient_identity_change SET reason = 'Synthetic changed'").update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void uhidIsNotEditable() throws Exception {
        UUID id = registered();
        update(reception, id, "{\"version\":0,\"uhid\":\"HMS-999999\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("UHID_IMMUTABLE")));
        update(reception, id, "{\"version\":0,\"uhid\":\"HMS-000001\",\"phone\":\"+910300000050\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(fx.auditActions(id)).containsExactly("PATIENT_CREATED");
    }

    @Test
    void staleVersionIsAConflictAndVersionIsRequired() throws Exception {
        UUID id = registered();
        update(contactOnly, id, "{\"version\":0,\"phone\":\"+910300000051\"}").andExpect(status().isOk());
        update(contactOnly, id, "{\"version\":0,\"phone\":\"+910300000052\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        update(contactOnly, id, "{\"phone\":\"+910300000052\"}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void nameMinimumAppliesAfterTrimming() throws Exception {
        UUID id = registered();
        update(reception, id, "{\"version\":0,\"fullName\":\" a \",\"identityChangeReason\":\"typo fix\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(
                "{\"fullName\":\" a \",\"sex\":\"F\",\"dateOfBirth\":\"1985-03-02\",\"phone\":\"+910300000071\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void concurrentUpdatesWithTheSameVersionGiveOneConflict() throws Exception {
        UUID id = registered();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String phone : List.of("+910300000061", "+910300000062")) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    return update(contactOnly, id, "{\"version\":0,\"phone\":\"" + phone + "\"}").andReturn()
                            .getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingPermissionAndOtherOrganizationsPatient() throws Exception {
        UUID id = registered();
        fx.role(org, "READER", "patient:read");
        update(fx.loggedIn(org, "reader", "READER"), id, "{\"version\":0,\"phone\":\"+910300000070\"}")
                .andExpect(status().isForbidden());
        UUID other = fx.organization();
        update(fx.loggedIn(other, "reception", "RECEPTIONIST"), id, "{\"version\":0,\"phone\":\"+910300000071\"}")
                .andExpect(status().isNotFound());
        update(reception, UUID.randomUUID(), "{\"version\":0,\"phone\":\"+910300000072\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void abhaCanBeSetChangedAndClearedAndStaysUniqueAndOutOfAudit() throws Exception {
        UUID id = registered();
        UUID other = registered("Kabir Nair", "1990-07-07", "+910300000002");
        update(contactOnly, id, "{\"version\":0,\"abhaNumber\":\"" + ABHA + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaLast4", is("9012")));
        update(contactOnly, other, "{\"version\":0,\"abhaNumber\":\"00-1234-5678-9012\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DUPLICATE_ABHA")));
        update(contactOnly, id, "{\"version\":1,\"abhaNumber\":\"00123456780000\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaLast4", is("0000")));
        update(contactOnly, other, "{\"version\":0,\"abhaNumber\":\"" + ABHA + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaLast4", is("9012")));
        String body = update(contactOnly, id, "{\"version\":2,\"abhaNumber\":\"\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.abhaLast4", nullValue()))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("00123456780000");
        update(contactOnly, id, "{\"version\":3,\"abhaNumber\":\"12345\"}")
                .andExpect(status().isUnprocessableContent());
        assertThat(jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? AND action = "
                + "'PATIENT_UPDATED'").param(id).query(String.class).list()).hasSize(3)
                .allSatisfy(fields -> assertThat(fields).contains("abhaNumber"));
        for (String audit : auditRows(id)) {
            assertThat(audit).doesNotContain("0012345678").doesNotContain("9012");
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT abha_hash IS NULL AND abha_encrypted IS NULL FROM hms.patient "
                + "WHERE id = ?").param(id).query(Boolean.class).single())).isTrue();
    }

    @Test
    void identityOrPhoneEditThatMatchesAnotherPatientIsASuspectedDuplicateUnlessConfirmed() throws Exception {
        UUID existing = registered("Ananya Rao", "1979-06-15", "+910300000081");
        UUID id = registered("Kabir Nair", "1990-07-07", "+910300000082");
        String change = "{\"version\":0,\"fullName\":\"Ananya Rau\",\"dateOfBirth\":\"1979-06-15\","
                + "\"identityChangeReason\":\"" + REASON + "\"";
        update(reception, id, change + "}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SUSPECTED")))
                .andExpect(jsonPath("$.candidates", hasSize(1)))
                .andExpect(jsonPath("$.candidates[0].id", is(existing.toString())));
        assertThat(fx.auditActions(id)).containsExactly("PATIENT_CREATED");

        update(reception, id, change + ",\"confirmNotDuplicate\":true}")
                .andExpect(status().isUnprocessableContent());
        update(reception, id, change + ",\"confirmNotDuplicate\":true,\"overrideReason\":\"Synthetic: twins\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName", is("Ananya Rau")));
        assertThat(fx.auditActions(id)).containsExactly("PATIENT_CREATED", "PATIENT_UPDATED",
                "PATIENT_IDENTITY_CHANGED", "PATIENT_DUPLICATE_OVERRIDDEN");

        // Its own row is never a candidate: an unrelated edit of the now-similar patient goes through.
        update(contactOnly, id, "{\"version\":1,\"emergencyContact\":{\"name\":\"Ravi Nair\",\"relationship\":"
                + "\"Father\",\"phone\":\"+910300000083\"}}").andExpect(status().isOk());
        // A phone change alone re-runs the check (same phone as Ananya Rao, similar name).
        update(contactOnly, id, "{\"version\":2,\"phone\":\"+910300000081\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DUPLICATE_SUSPECTED")));
        // Confirming needs patient:override-duplicate.
        update(contactOnly, id, "{\"version\":2,\"phone\":\"+910300000081\",\"confirmNotDuplicate\":true,"
                + "\"overrideReason\":\"Synthetic: shared family phone\"}").andExpect(status().isForbidden());
    }

    @Test
    void requestThatChangesNothingKeepsTheVersionAndAuditsOnlyTheView() throws Exception {
        UUID id = registered();
        update(contactOnly, id, "{\"version\":0,\"phone\":\"+910300000001\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(0)));
        assertThat(fx.auditActions(id)).containsExactly("PATIENT_CREATED", "PATIENT_VIEWED"); // no PATIENT_UPDATED
    }
}
