package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.billing.api.InvoiceBalancePort;
import com.hms.billing.api.InvoiceBalancePort.InvoiceBalance;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.Money;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/** [E7-1] Invoice drafts: create from an encounter, replace items, catalog prices, totals, discard. Synthetic data. */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceDraftIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String INVOICES = "/api/v1/invoices";

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
    InvoiceBalancePort balances;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session cashier;
    private AdminApiFixture.Session supervisor;
    private AdminApiFixture.Session reader;
    private UUID patient;
    private UUID otherPatient;
    private UUID practitioner;
    private UUID consultation;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "CASHIER", "invoice:create", "invoice:read", "invoice:update", "catalog:manage",
                "catalog:read");
        fx.role(org, "BILLSUP", "invoice:create", "invoice:read", "invoice:update", "invoice:price-override",
                "invoice:discount");
        fx.role(org, "BILLREAD", "invoice:read");
        cashier = fx.loggedIn(org, "cashier", "CASHIER");
        supervisor = fx.loggedIn(org, "supervisor", "BILLSUP");
        reader = fx.loggedIn(org, "reader", "BILLREAD");
        patient = insertPatient("HMS-000101");
        otherPatient = insertPatient("HMS-000102");
        practitioner = insertPractitioner();
        consultation = insertService("CONSULT", "Synthetic Consultation", "33.33");
    }

    @Test
    void draftIsCreatedForAnEncounterOfThePatientAndAudited() throws Exception {
        UUID encounter = insertEncounter(patient);
        String body = create(patient, encounter).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("DRAFT"))).andExpect(jsonPath("$.type", is("OPD")))
                .andExpect(jsonPath("$.patientId", is(patient.toString())))
                .andExpect(jsonPath("$.encounterId", is(encounter.toString())))
                .andExpect(jsonPath("$.total", is("0.00"))).andExpect(jsonPath("$.subtotal", is("0.00")))
                .andExpect(jsonPath("$.invoiceNo", nullValue())).andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.items", hasSize(0))).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(id.version()).isEqualTo(7);
        assertThat(fx.auditActions(id)).containsExactly("INVOICE_CREATED");
        // One live invoice per encounter.
        create(patient, encounter).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void encounterMustExistAndBelongToThePatient() throws Exception {
        UUID encounter = insertEncounter(patient);
        create(otherPatient, encounter).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        create(patient, UUID.randomUUID()).andExpect(status().isNotFound());
        create(UUID.randomUUID(), encounter).andExpect(status().isNotFound());
        call(cashier, post(INVOICES), "{\"type\":\"IPD\",\"patientId\":\"" + patient + "\",\"encounterId\":\""
                + encounter + "\"}").andExpect(status().isUnprocessableContent());
        call(cashier, post(INVOICES), "{\"type\":\"OPD\",\"patientId\":\"" + patient + "\"}")
                .andExpect(status().isUnprocessableContent());
        call(reader, post(INVOICES), "{\"type\":\"OPD\",\"patientId\":\"" + patient + "\",\"encounterId\":\""
                + encounter + "\"}").andExpect(status().isForbidden());
    }

    @Test
    void itemsArePricedFromTheCatalogAndTotalsAreSumsOfRoundedLines() throws Exception {
        UUID id = newDraft();
        putItems(supervisor, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"3\",\"taxRate\":\"18\"},"
                + "{\"serviceId\":\"" + consultation + "\",\"description\":\"Follow-up\",\"quantity\":\"0.5\","
                + "\"discount\":\"1.00\",\"discountReason\":\"Synthetic goodwill\",\"taxRate\":\"0\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].description", is("Synthetic Consultation")))
                .andExpect(jsonPath("$.items[0].unitPrice", is("33.33")))
                .andExpect(jsonPath("$.items[0].quantity", is("3.00")))
                .andExpect(jsonPath("$.items[0].taxRate", is("18.00")))
                .andExpect(jsonPath("$.items[0].gross", is("99.99")))
                .andExpect(jsonPath("$.items[0].tax", is("18.00")))
                .andExpect(jsonPath("$.items[0].total", is("117.99")))
                .andExpect(jsonPath("$.items[1].description", is("Follow-up")))
                .andExpect(jsonPath("$.items[1].gross", is("16.67")))
                .andExpect(jsonPath("$.items[1].total", is("15.67")))
                .andExpect(jsonPath("$.subtotal", is("116.66")))
                .andExpect(jsonPath("$.discountTotal", is("1.00")))
                .andExpect(jsonPath("$.taxTotal", is("18.00")))
                .andExpect(jsonPath("$.total", is("133.66")))
                .andExpect(jsonPath("$.version", is(1)));
        List<UUID> itemIds = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.invoice_item WHERE invoice_id = ?")
                .param(id).query(UUID.class).list());
        assertThat(itemIds).hasSize(2).allSatisfy(i -> assertThat(i.version()).isEqualTo(7));
        assertThat(fx.auditActions(id)).containsExactly("INVOICE_CREATED", "INVOICE_ITEMS_UPDATED",
                "INVOICE_DISCOUNT_APPLIED");
        // Replacing items replaces, it does not append.
        putItems(cashier, id, 1, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.total", is("33.33")));
        call(reader, get(INVOICES + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is("33.33"))).andExpect(jsonPath("$.items", hasSize(1)));
    }

    @Test
    void priceChangeDoesNotAlterExistingDraft() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isOk());
        call(cashier, post("/api/v1/services/" + consultation + "/prices"), "{\"amount\":\"750.00\"}")
                .andExpect(status().isCreated());
        call(reader, get(INVOICES + "/" + id), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice", is("33.33"))).andExpect(jsonPath("$.total", is("33.33")))
                .andExpect(jsonPath("$.version", is(1)));
        // A new draft picks up the new price.
        UUID next = newDraft();
        putItems(cashier, next, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(jsonPath("$.items[0].unitPrice", is("750.00")));
    }

    @Test
    void aPriceDifferentFromTheCatalogNeedsPriceOverride() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"unitPrice\":\"10.00\"}")
                .andExpect(status().isForbidden());
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"unitPrice\":\"33.33\"}")
                .andExpect(status().isOk());
        putItems(cashier, id, 1, "{\"description\":\"Manual charge\",\"quantity\":\"1\",\"unitPrice\":\"5.00\"}")
                .andExpect(status().isForbidden());
        putItems(supervisor, id, 1, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"unitPrice\":\"10.00\"},"
                + "{\"description\":\"Manual charge\",\"quantity\":\"2\",\"unitPrice\":\"5.00\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.total", is("20.00")));
    }

    @Test
    void itemRulesAreValidated() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"0\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].quantity")));
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"10000\"}")
                .andExpect(status().isUnprocessableContent());
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1.005\"}")
                .andExpect(status().isUnprocessableContent());
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"taxRate\":\"100.01\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].taxRate")));
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\",\"discount\":\"33.34\","
                + "\"discountReason\":\"Synthetic goodwill\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].discount")));
        putItems(cashier, id, 0, "{\"serviceId\":\"" + UUID.randomUUID() + "\",\"quantity\":\"1\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].serviceId")));
        putItems(supervisor, id, 0, "{\"quantity\":\"1\",\"unitPrice\":\"5.00\"}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field", is("items[0].description")));
        call(cashier, put(INVOICES + "/" + id + "/items"), "{\"items\":[]}")
                .andExpect(status().isUnprocessableContent());
        call(reader, get(INVOICES + "/" + id), null).andExpect(jsonPath("$.version", is(0)));
    }

    @Test
    void staleVersionIsAConflictAndReadersCannotWrite() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isOk());
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"2\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        putItems(reader, id, 1, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"2\"}")
                .andExpect(status().isForbidden());
        call(cashier, get(INVOICES + "/" + UUID.randomUUID()), null).andExpect(status().isNotFound());
    }

    @Test
    void discardedDraftIsImmutableAlsoInTheDatabase() throws Exception {
        UUID id = newDraft();
        call(cashier, post(INVOICES + "/" + id + "/discard"), "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DISCARDED"))).andExpect(jsonPath("$.version", is(1)));
        putItems(cashier, id, 1, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"1\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVOICE_IMMUTABLE")));
        call(cashier, post(INVOICES + "/" + id + "/discard"), "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVOICE_IMMUTABLE")));
        assertThat(fx.auditActions(id)).containsExactly("INVOICE_CREATED", "INVOICE_DISCARDED");
        assertThat(sqlState("INSERT INTO hms.invoice_item (id, organization_id, invoice_id, position, description,"
                + " quantity, unit_price, discount, tax_rate, gross, taxable, tax, total, created_at) VALUES ('"
                + UUID.randomUUID() + "', '" + org + "', '" + id + "', 0, 'x', 1, 1, 0, 0, 1, 1, 0, 1, now())"))
                .isEqualTo("HM004");
        assertThat(sqlState("UPDATE hms.invoice SET status = 'DRAFT' WHERE id = '" + id + "'")).isEqualTo("HM004");
        assertThat(sqlState("DELETE FROM hms.invoice WHERE id = '" + id + "'")).isNotNull();
        // A discarded draft frees the encounter for a new invoice.
        UUID encounter = fx.inOrg(org, () -> jdbc.sql("SELECT encounter_id FROM hms.invoice WHERE id = ?").param(id)
                .query(UUID.class).single());
        create(patient, encounter).andExpect(status().isCreated());
    }

    @Test
    void listFiltersByPatientAndStatus() throws Exception {
        UUID first = newDraft();
        UUID second = newDraft();
        call(cashier, post(INVOICES + "/" + second + "/discard"), "{\"version\":0}").andExpect(status().isOk());
        UUID encounter = insertEncounter(otherPatient);
        create(otherPatient, encounter).andExpect(status().isCreated());
        call(reader, get(INVOICES).param("patientId", patient.toString()), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(2))).andExpect(jsonPath("$.items", hasSize(2)));
        call(reader, get(INVOICES).param("patientId", patient.toString()).param("status", "DRAFT"), null)
                .andExpect(jsonPath("$.totalItems", is(1)))
                .andExpect(jsonPath("$.items[0].id", is(first.toString())));
        call(reader, get(INVOICES).param("size", "101"), null).andExpect(status().isUnprocessableContent());
    }

    @Test
    void balancePortLocksAndAppliesPaymentStatusOnlyToIssuedInvoices() throws Exception {
        UUID id = newDraft();
        putItems(cashier, id, 0, "{\"serviceId\":\"" + consultation + "\",\"quantity\":\"3\",\"taxRate\":\"18\"}")
                .andExpect(status().isOk());
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            balances.applyPaymentStatus(org, id, "PAID");
            return null;
        })).isInstanceOf(ConflictException.class);
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.invoice SET status = 'ISSUED', invoice_no = '2026-27/000001',"
                + " issued_at = now(), issued_by = ? WHERE id = ?").params(cashier.userId(), id).update());
        InvoiceBalance balance = fx.inOrg(org, () -> balances.lockForPayment(org, id));
        assertThat(balance.status()).isEqualTo("ISSUED");
        assertThat(balance.total()).isEqualTo(Money.of("117.99"));
        assertThat(balance.paid()).isEqualTo(Money.ZERO);
        assertThat(balance.refunded()).isEqualTo(Money.ZERO);
        fx.inOrg(org, () -> {
            balances.applyPaymentStatus(org, id, "PARTIALLY_PAID");
            return null;
        });
        assertThat(fx.inOrg(org, () -> balances.lockForPayment(org, id).status())).isEqualTo("PARTIALLY_PAID");
        assertThatThrownBy(() -> fx.inOrg(org, () -> balances.lockForPayment(org, UUID.randomUUID())))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            balances.applyPaymentStatus(org, id, "DRAFT");
            return null;
        })).isInstanceOf(ConflictException.class);
        // An issued invoice's totals are frozen in the database.
        assertThat(sqlState("UPDATE hms.invoice SET total = 1 WHERE id = '" + id + "'")).isEqualTo("HM004");
    }

    // --- helpers ---

    private UUID newDraft() throws Exception {
        UUID encounter = insertEncounter(patient);
        String body = create(patient, encounter).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private ResultActions create(UUID patientId, UUID encounterId) throws Exception {
        return call(cashier, post(INVOICES), "{\"type\":\"OPD\",\"patientId\":\"" + patientId
                + "\",\"encounterId\":\"" + encounterId + "\"}");
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

    private UUID insertPatient(String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Billing Patient', 'U', DATE '1990-01-01', '+910000000081', ?, now(), now())""")
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
