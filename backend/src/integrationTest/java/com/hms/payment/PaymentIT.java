package com.hms.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
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

/** [E7-3] Payments: partial, idempotent, row-locked. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class PaymentIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String INVOICES = "/api/v1/invoices";
    private static final String RECEIPT = "RCT-\\d{4}-\\d{2}/\\d{6}";

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
    private AdminApiFixture.Session cashier;
    private AdminApiFixture.Session clerk;
    private UUID patient;
    private UUID practitioner;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "CASHIER", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "payment:create", "payment:read");
        fx.role(org, "CLERK", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "payment:read");
        cashier = fx.loggedIn(org, "cashier", "CASHIER");
        clerk = fx.loggedIn(org, "clerk", "CLERK");
        patient = insertPatient();
        practitioner = insertPractitioner();
    }

    @Test
    void partialThenFullPaymentMovesTheInvoiceAndAudits() throws Exception {
        UUID id = issuedInvoice("1000.00");
        String first = pay(cashier, id, "k-" + UUID.randomUUID(), "300.00", "UPI", "TEST-UPI-0001")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo", matchesPattern(RECEIPT)))
                .andExpect(jsonPath("$.amount", is("300.00")))
                .andExpect(jsonPath("$.outstanding", is("700.00")))
                .andExpect(jsonPath("$.invoiceStatus", is("PARTIALLY_PAID")))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.receiptNo")).endsWith("/000001");
        pay(cashier, id, "k-" + UUID.randomUUID(), "800.00", "CASH", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("EXCEEDS_OUTSTANDING")))
                .andExpect(jsonPath("$.detail", is("Outstanding is 700.00.")));
        pay(cashier, id, "k-" + UUID.randomUUID(), "700.00", "CASH", null).andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo", matchesPattern(RECEIPT)))
                .andExpect(jsonPath("$.outstanding", is("0.00")))
                .andExpect(jsonPath("$.invoiceStatus", is("PAID")));
        pay(cashier, id, "k-" + UUID.randomUUID(), "1.00", "CASH", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(invoiceStatus(id)).isEqualTo("PAID");
        UUID paymentId = UUID.fromString(JsonPath.read(first, "$.id"));
        assertThat(fx.auditActions(paymentId)).containsExactly("PAYMENT_RECORDED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(paymentId).query(String.class).list();
        assertThat(rows).hasSize(1).noneMatch(r -> r.contains("TEST-UPI-0001") || r.contains("300.00"));
        assertThat(sqlState("UPDATE hms.payment SET amount = 1 WHERE id = '" + paymentId + "'")).isNotNull();
        assertThat(sqlState("DELETE FROM hms.payment WHERE id = '" + paymentId + "'")).isNotNull();
    }

    @Test
    void twoConcurrent600PaymentsOn1000OutstandingOneFails() throws Exception {
        UUID id = issuedInvoice("1000.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = pay(cashier, id, "c-" + UUID.randomUUID(), "600.00", "CASH", null).andReturn()
                            .getResponse();
                    return response.getStatus() == 201 ? "201"
                            : response.getStatus() + " " + JsonPath.read(response.getContentAsString(), "$.code");
                }));
            }
            for (Future<String> f : results) {
                outcomes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(outcomes).containsExactlyInAnyOrder("201", "409 EXCEEDS_OUTSTANDING");
        assertThat(paymentCount(id)).isEqualTo(1);
        assertThat(invoiceStatus(id)).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    void replayingAKeyReturnsTheSameReceiptAndOneRow() throws Exception {
        UUID id = issuedInvoice("1000.00");
        UUID other = issuedInvoice("1000.00");
        String key = "replay-" + UUID.randomUUID();
        String first = pay(cashier, id, key, "100.00", "CARD", "TEST-CARD-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = pay(cashier, id, key, "100.00", "CARD", "TEST-CARD-1").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.receiptNo")).isEqualTo(JsonPath.read(first, "$.receiptNo"));
        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat(paymentCount(id)).isEqualTo(1);
        pay(cashier, id, key, "200.00", "CARD", "TEST-CARD-1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        pay(cashier, other, key, "100.00", "CARD", "TEST-CARD-1").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
    }

    @Test
    void paymentOnADraftIsAConflict() throws Exception {
        UUID draft = draftWithItem("100.00");
        pay(cashier, draft, "k-" + UUID.randomUUID(), "10.00", "CASH", null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(paymentCount(draft)).isZero();
    }

    @Test
    void unknownOrOtherOrganizationInvoiceIs404AndPermissionIsRequired() throws Exception {
        UUID id = issuedInvoice("100.00");
        pay(cashier, UUID.randomUUID(), "k-" + UUID.randomUUID(), "10.00", "CASH", null)
                .andExpect(status().isNotFound());
        UUID otherOrg = fx.organization();
        fx.role(otherOrg, "CASHIER", "payment:create", "payment:read");
        AdminApiFixture.Session outsider = fx.loggedIn(otherOrg, "cashier", "CASHIER");
        pay(outsider, id, "k-" + UUID.randomUUID(), "10.00", "CASH", null).andExpect(status().isNotFound());
        pay(clerk, id, "k-" + UUID.randomUUID(), "10.00", "CASH", null).andExpect(status().isForbidden());
        assertThat(paymentCount(id)).isZero();
    }

    @Test
    void requestValidation() throws Exception {
        UUID id = issuedInvoice("100.00");
        mockMvc.perform(cashier.apply(post(INVOICES + "/" + id + "/payments")).contentType(JSON)
                .content("{\"amount\":\"10.00\",\"mode\":\"CASH\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        pay(cashier, id, "k-" + UUID.randomUUID(), "0.00", "CASH", null).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        pay(cashier, id, "k-" + UUID.randomUUID(), "10.00", "BARTER", null)
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        pay(cashier, id, "k-" + UUID.randomUUID(), "10.00", "UPI", "X".repeat(51))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        assertThat(paymentCount(id)).isZero();
    }

    @Test
    void listFiltersByModeAndIsPaginated() throws Exception {
        UUID id = issuedInvoice("1000.00");
        pay(cashier, id, "k-" + UUID.randomUUID(), "10.00", "CASH", null).andExpect(status().isCreated());
        pay(cashier, id, "k-" + UUID.randomUUID(), "20.00", "UPI", "TEST-UPI-2").andExpect(status().isCreated());
        mockMvc.perform(clerk.apply(get("/api/v1/payments")).param("mode", "UPI")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].amount", is("20.00")))
                .andExpect(jsonPath("$.items[0].invoiceId", is(id.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
        mockMvc.perform(clerk.apply(get("/api/v1/payments")).param("size", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.totalItems", is(2)));
        mockMvc.perform(clerk.apply(get("/api/v1/payments")).param("from", "2000-01-01T00:00:00Z")
                .param("to", "2000-01-02T00:00:00Z")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(clerk.apply(get("/api/v1/payments")).param("size", "101"))
                .andExpect(status().isUnprocessableContent());
        mockMvc.perform(clerk.apply(get("/api/v1/payments")).param("mode", "BARTER"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- helpers ---

    private ResultActions pay(AdminApiFixture.Session who, UUID invoice, String key, String amount, String mode,
            String referenceNo) throws Exception {
        String body = "{\"amount\":\"" + amount + "\",\"mode\":\"" + mode + "\""
                + (referenceNo == null ? "" : ",\"referenceNo\":\"" + referenceNo + "\"") + "}";
        return mockMvc.perform(who.apply(post(INVOICES + "/" + invoice + "/payments")).header("Idempotency-Key", key)
                .contentType(JSON).content(body));
    }

    private UUID issuedInvoice(String amount) throws Exception {
        UUID id = draftWithItem(amount);
        mockMvc.perform(cashier.apply(post(INVOICES + "/" + id + "/issue"))
                .header("Idempotency-Key", "issue-" + UUID.randomUUID())).andExpect(status().isOk());
        return id;
    }

    private UUID draftWithItem(String amount) throws Exception {
        UUID encounter = insertEncounter();
        String body = mockMvc.perform(cashier.apply(post(INVOICES)).contentType(JSON).content(
                "{\"type\":\"OPD\",\"patientId\":\"" + patient + "\",\"encounterId\":\"" + encounter + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        mockMvc.perform(cashier.apply(put(INVOICES + "/" + id + "/items")).contentType(JSON)
                .content("{\"version\":0,\"items\":[{\"description\":\"Synthetic charge\",\"quantity\":\"1\","
                        + "\"unitPrice\":\"" + amount + "\",\"taxRate\":\"0\"}]}"))
                .andExpect(status().isOk());
        return id;
    }

    private String invoiceStatus(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.invoice WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private int paymentCount(UUID invoice) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.payment WHERE invoice_id = ?").param(invoice)
                .query(Integer.class).single());
    }

    private String sqlState(String sql) {
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
                VALUES (?, ?, 'HMS-000301', 'Test Payment Patient', 'U', DATE '1990-01-01', '+910000000083', ?, now(),
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
                    + " VALUES (?, ?, ?, 'Dr. Test Payment', ?, ?, now(), now())")
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
                .params(id, org, patient, practitioner, cashier.userId()).update());
        return id;
    }
}
