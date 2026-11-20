package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E7-8] Discounts: a line discount needs invoice:discount and a reason, an invoice-level percent distributes
 * half-up to the lines, and amounts that would overflow NUMERIC(14,2) are field errors, not 500. Synthetic data.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceDiscountIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String INVOICES = "/api/v1/invoices";
    private static final String MAX = "999999999999.99";

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
    private AdminApiFixture.Session discounter;
    private AdminApiFixture.Session supervisor;
    private UUID patient;
    private UUID practitioner;
    private UUID consultation;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "CASHIER", "invoice:create", "invoice:read", "invoice:update", "catalog:manage",
                "catalog:read");
        fx.role(org, "DISCOUNTER", "invoice:create", "invoice:read", "invoice:update", "invoice:discount");
        fx.role(org, "BILLSUP", "invoice:create", "invoice:read", "invoice:update", "invoice:price-override",
                "invoice:discount");
        cashier = fx.loggedIn(org, "cashier", "CASHIER");
        discounter = fx.loggedIn(org, "discounter", "DISCOUNTER");
        supervisor = fx.loggedIn(org, "supervisor", "BILLSUP");
        patient = insertPatient("HMS-000201");
        practitioner = insertPractitioner();
        consultation = insertService("CONSULT", "Synthetic Consultation", "100.00");
    }

    @Test
    void lineDiscountNeedsThePermission() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, line("10.00", "Synthetic goodwill")).andExpect(status().isForbidden());
        // A zero discount needs neither the permission nor a reason.
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isOk());
        putItems(discounter, id, 1, line("10.00", "Synthetic goodwill")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].discount", is("10.00")))
                .andExpect(jsonPath("$.items[0].discountReason", is("Synthetic goodwill")))
                .andExpect(jsonPath("$.total", is("90.00"))).andExpect(jsonPath("$.discountTotal", is("10.00")));
        assertThat(fx.auditActions(id)).containsExactly("INVOICE_CREATED", "INVOICE_ITEMS_UPDATED",
                "INVOICE_ITEMS_UPDATED", "INVOICE_DISCOUNT_APPLIED");
        assertThat(auditMentions(id, "goodwill")).isFalse();
    }

    @Test
    void lineDiscountNeedsAReasonOfAtMost200Characters() throws Exception {
        UUID id = newDraft();
        putItems(discounter, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\","
                + "\"discount\":\"5.00\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")))
                .andExpect(jsonPath("$.errors[0].field", is("items[0].discountReason")));
        putItems(discounter, id, 0, line("5.00", "   ")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].discountReason")));
        putItems(discounter, id, 0, line("5.00", "x".repeat(201))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].discountReason")));
        putItems(discounter, id, 0, line("5.00", "x".repeat(200))).andExpect(status().isOk());
        // Validation (422) is answered before the permission check (403).
        putItems(cashier, id, 1, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\","
                + "\"discount\":\"5.00\"}").andExpect(status().isUnprocessableContent());
    }

    @Test
    void discountAboveTheLineAmountIsABusinessRuleViolation() throws Exception {
        UUID id = newDraft();
        putItems(discounter, id, 0, line("100.01", "Synthetic goodwill")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.errors[0].field", is("items[0].discount")));
        putItems(discounter, id, 0, line("100.00", "Synthetic goodwill")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is("0.00")));
    }

    @Test
    void percentDiscountIsDistributedHalfUpPerLine() throws Exception {
        UUID id = newDraft();
        putItems(supervisor, id, 0, "{\"description\":\"Odd price\",\"quantity\":\"1\",\"unitPrice\":\"10.05\","
                + "\"taxRate\":\"18\"},{\"serviceId\":\"" + consultation + "\",\"quantity\":\"2\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));
        discount(discounter, id, 1, "10", "Synthetic staff concession").andExpect(status().isOk())
                .andExpect(jsonPath("$.version", is(2)))
                .andExpect(jsonPath("$.items[0].discount", is("1.01")))
                .andExpect(jsonPath("$.items[0].discountReason", is("Synthetic staff concession")))
                .andExpect(jsonPath("$.items[0].taxable", is("9.04")))
                .andExpect(jsonPath("$.items[0].tax", is("1.63")))
                .andExpect(jsonPath("$.items[0].total", is("10.67")))
                .andExpect(jsonPath("$.items[1].discount", is("20.00")))
                .andExpect(jsonPath("$.items[1].total", is("180.00")))
                .andExpect(jsonPath("$.discountTotal", is("21.01")))
                .andExpect(jsonPath("$.subtotal", is("210.05")))
                .andExpect(jsonPath("$.total", is("190.67")));
        assertThat(fx.auditActions(id)).endsWith("INVOICE_DISCOUNT_APPLIED");
        assertThat(auditMentions(id, "concession")).isFalse();
        // 0 percent clears the discounts; the reason is still required.
        discount(discounter, id, 2, "0", "Synthetic reversal").andExpect(status().isOk())
                .andExpect(jsonPath("$.discountTotal", is("0.00")))
                .andExpect(jsonPath("$.items[0].discountReason", nullValue()))
                .andExpect(jsonPath("$.total", is("211.86")));
        discount(discounter, id, 3, "100", "Synthetic waiver").andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].total", is("0.00")))
                .andExpect(jsonPath("$.items[1].total", is("0.00")));
    }

    @Test
    void percentDiscountIsValidated() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isOk());
        discount(cashier, id, 1, "10", "Synthetic goodwill").andExpect(status().isForbidden());
        for (String percent : new String[] {"100.01", "-1", "10.005", "abc", ""}) {
            discount(discounter, id, 1, percent, "Synthetic goodwill").andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field", is("percent")));
        }
        discount(discounter, id, 1, "10", "").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("reason")));
        discount(discounter, id, 1, "10", "x".repeat(201)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("reason")));
        discount(discounter, id, 0, "10", "Synthetic goodwill").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        call(discounter, put(INVOICES + "/" + UUID.randomUUID() + "/discount"),
                "{\"version\":0,\"percent\":\"10\",\"reason\":\"r\"}").andExpect(status().isNotFound());
        call(discounter, put(INVOICES + "/" + id + "/discount"), "{\"percent\":\"10\",\"reason\":\"r\"}")
                .andExpect(status().isUnprocessableContent());
        call(cashier, post(INVOICES + "/" + id + "/discard"), "{\"version\":1}").andExpect(status().isOk());
        discount(discounter, id, 2, "10", "Synthetic goodwill").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVOICE_IMMUTABLE")));
        // An invoice without lines has nothing to discount.
        UUID empty = newDraft();
        discount(discounter, empty, 0, "10", "Synthetic goodwill").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
    }

    @Test
    void amountsBeyondNumeric14AreFieldErrorsNotServerErrors() throws Exception {
        UUID id = newDraft();
        // unitPrice x quantity beyond the cap.
        putItems(supervisor, id, 0, "{\"description\":\"Big\",\"quantity\":\"2\",\"unitPrice\":\"" + MAX + "\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].unitPrice")));
        putItems(supervisor, id, 0, "{\"description\":\"Big\",\"quantity\":\"9999\",\"unitPrice\":\"999999999.99\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].unitPrice")));
        // The line total including tax beyond the cap.
        putItems(supervisor, id, 0, "{\"description\":\"Big\",\"quantity\":\"1\",\"unitPrice\":\"" + MAX
                + "\",\"taxRate\":\"18\"}").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].unitPrice")));
        // Each line fits, the invoice does not.
        putItems(supervisor, id, 0, "{\"description\":\"A\",\"quantity\":\"1\",\"unitPrice\":\"600000000000.00\"},"
                + "{\"description\":\"B\",\"quantity\":\"1\",\"unitPrice\":\"600000000000.00\"}")
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.errors[0].field", is("items")));
        // Exactly at the cap is fine.
        putItems(supervisor, id, 0, "{\"description\":\"Max\",\"quantity\":\"1\",\"unitPrice\":\"" + MAX + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total", is(MAX)));
    }

    // --- helpers ---

    private String line(String discount, String reason) {
        return "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"discount\":\"" + discount
                + "\",\"discountReason\":\"" + reason + "\"}";
    }

    private ResultActions discount(AdminApiFixture.Session who, UUID id, long version, String percent,
            String reason) throws Exception {
        return call(who, put(INVOICES + "/" + id + "/discount"), "{\"version\":" + version + ",\"percent\":\""
                + percent + "\",\"reason\":\"" + reason + "\"}");
    }

    private boolean auditMentions(UUID id, String text) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE entity_id = ?"
                + " AND audit_event::text ILIKE ?").params(id, "%" + text + "%").query(Long.class).single()) > 0;
    }

    private UUID newDraft() throws Exception {
        UUID encounter = insertEncounter(patient);
        String body = call(cashier, post(INVOICES), "{\"type\":\"OPD\",\"patientId\":\"" + patient
                + "\",\"encounterId\":\"" + encounter + "\"}").andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions putItems(AdminApiFixture.Session who, UUID id, long version, String items)
            throws Exception {
        return call(who, put(INVOICES + "/" + id + "/items"),
                "{\"version\":" + version + ",\"items\":[" + items + "]}");
    }

    private ResultActions call(AdminApiFixture.Session who, MockHttpServletRequestBuilder request, String body)
            throws Exception {
        if (body != null) {
            request = request.contentType(JSON).content(body);
        }
        return mockMvc.perform(who.apply(request));
    }

    private UUID insertPatient(String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Billing Patient', 'U', DATE '1990-01-01', '+910000000082', ?, now(), now())""")
                .params(id, org, uhid, UUID.randomUUID()).update());
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
                    + " VALUES (?, ?, ?, 'Dr. Test Billing', ?, ?, now(), now())")
                    .params(id, org, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user).update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(org, id, specialty).update();
        });
        return id;
    }

    private UUID insertEncounter(UUID patientId) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                    created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                .params(id, org, patientId, practitioner, cashier.userId()).update());
        return id;
    }

    private UUID insertService(String code, String name, String amount) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> {
            jdbc.sql("INSERT INTO hms.service_catalog_item (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, ?, now(), now())").params(id, org, code, name).update();
            return jdbc.sql("INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from,"
                    + " created_by, created_at) VALUES (?, ?, ?, ?::numeric, now() - interval '1 day', ?, now())")
                    .params(UUID.randomUUID(), org, id, amount, cashier.userId()).update();
        });
        return id;
    }
}
