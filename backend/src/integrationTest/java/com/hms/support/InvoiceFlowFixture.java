package com.hms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Issued invoices and payments through the API for billing/payment ITs ([E7-4]). Synthetic data only. */
public final class InvoiceFlowFixture {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String INVOICES = "/api/v1/invoices";

    private final MockMvc mockMvc;
    private final JdbcClient jdbc;
    private final AdminApiFixture fx;
    private final UUID org;
    private final AdminApiFixture.Session biller;
    private final UUID patient;
    private final UUID practitioner;

    /** {@code biller} needs invoice:create, invoice:update, invoice:issue and invoice:price-override. */
    public InvoiceFlowFixture(MockMvc mockMvc, JdbcClient jdbc, AdminApiFixture fx, UUID org,
            AdminApiFixture.Session biller) {
        this.mockMvc = mockMvc;
        this.jdbc = jdbc;
        this.fx = fx;
        this.org = org;
        this.biller = biller;
        this.patient = insertPatient();
        this.practitioner = insertPractitioner();
    }

    public UUID issuedInvoice(String amount) throws Exception {
        UUID id = draftWithItem(amount);
        mockMvc.perform(biller.apply(post(INVOICES + "/" + id + "/issue"))
                .header("Idempotency-Key", "issue-" + UUID.randomUUID())).andExpect(status().isOk());
        return id;
    }

    public UUID draftWithItem(String amount) throws Exception {
        return draftWithItem(amount, "0");
    }

    /** A DRAFT with one catalog-free line of {@code amount} taxed at {@code taxRate} percent. */
    public UUID draftWithItem(String amount, String taxRate) throws Exception {
        UUID encounter = insertEncounter();
        String body = mockMvc.perform(biller.apply(post(INVOICES)).contentType(JSON).content(
                "{\"type\":\"OPD\",\"patientId\":\"" + patient + "\",\"encounterId\":\"" + encounter + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(biller.apply(put(INVOICES + "/" + id + "/items")).contentType(JSON)
                .content("{\"version\":0,\"items\":[{\"description\":\"Synthetic charge\",\"quantity\":\"1\","
                        + "\"unitPrice\":\"" + amount + "\",\"taxRate\":\"" + taxRate + "\"}]}"))
                .andExpect(status().isOk());
        return id;
    }

    public ResultActions pay(AdminApiFixture.Session who, UUID invoice, String amount) throws Exception {
        return mockMvc.perform(who.apply(post(INVOICES + "/" + invoice + "/payments"))
                .header("Idempotency-Key", "pay-" + UUID.randomUUID()).contentType(JSON)
                .content("{\"amount\":\"" + amount + "\",\"mode\":\"CASH\"}"));
    }

    /** Pays {@code amount} with {@code who} and returns the payment id. */
    public UUID paid(AdminApiFixture.Session who, UUID invoice, String amount) throws Exception {
        String body = pay(who, invoice, amount).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    public String invoiceStatus(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.invoice WHERE id = ?").param(id)
                .query(String.class).single());
    }

    /** The SQLSTATE the statement fails with, or null when it succeeds. */
    public String sqlState(String sql) {
        try {
            fx.inOrg(org, () -> jdbc.sql(sql).update());
            return null;
        } catch (RuntimeException e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof SQLException s) {
                    return s.getSQLState();
                }
            }
            return "UNKNOWN";
        }
    }

    private UUID insertPatient() {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, 'HMS-000401', 'Test Refund Patient', 'U', DATE '1990-01-01', '+910000000084', ?, now(),
                    now())""").params(id, org, UUID.randomUUID()).update());
        return id;
    }

    private UUID insertPractitioner() {
        UUID id = UUID.randomUUID();
        UUID user = fx.user(org, fx.uniqueEmail("doctor"));
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Test Medicine', now(), now())").params(specialty, org,
                    "G" + specialty.toString().substring(0, 8).toUpperCase()).update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name,"
                    + " registration_no, created_by, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Dr. Test Refund', ?, ?, now(), now())")
                    .params(id, org, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user).update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(org, id, specialty).update();
        });
        return id;
    }

    private UUID insertEncounter() {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                    created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                .params(id, org, patient, practitioner, biller.userId()).update());
        return id;
    }
}
