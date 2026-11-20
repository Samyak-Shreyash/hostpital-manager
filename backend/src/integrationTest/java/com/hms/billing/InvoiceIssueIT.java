package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.billing.api.InvoiceIssued;
import com.hms.billing.application.InvoiceService;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/** [E7-2] Issue invoices: gapless numbers, freeze, guard triggers, Idempotency-Key. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceIssueIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String INVOICES = "/api/v1/invoices";

    @TestConfiguration
    static class Events {
        @Bean
        IssuedEvents issuedEvents() {
            return new IssuedEvents();
        }
    }

    static class IssuedEvents {
        final List<InvoiceIssued> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(InvoiceIssued event) {
            received.add(event);
        }
    }

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
    InvoiceService invoiceService;

    @Autowired
    IssuedEvents events;

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
                "invoice:price-override");
        fx.role(org, "CLERK", "invoice:create", "invoice:read", "invoice:update");
        cashier = fx.loggedIn(org, "cashier", "CASHIER");
        clerk = fx.loggedIn(org, "clerk", "CLERK");
        patient = insertPatient();
        practitioner = insertPractitioner();
    }

    @Test
    void issueFreezesTheInvoiceAssignsAFiscalYearNumberAuditsAndPublishes() throws Exception {
        UUID id = draftWithItem();
        String body = issue(cashier, id, "key-" + UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ISSUED")))
                .andExpect(jsonPath("$.invoiceNo", matchesPattern("\\d{4}-\\d{2}/000001")))
                .andExpect(jsonPath("$.total", is("118.00")))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.issuedAt")).isNotNull();
        assertThat(fx.auditActions(id)).endsWith("INVOICE_ISSUED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?"
                + " AND action = 'INVOICE_ISSUED'").param(id).query(String.class).list();
        assertThat(rows).hasSize(1).noneMatch(r -> r.contains("Synthetic charge"));
        assertThat(events.received).anyMatch(e -> e.invoiceId().equals(id) && e.organizationId().equals(org)
                && e.invoiceNo().equals(JsonPath.read(body, "$.invoiceNo")));
        // The period key is the org-time-zone financial year of the issue instant.
        String period = fx.inOrg(org, () -> jdbc.sql("SELECT period_key FROM hms.document_sequence"
                + " WHERE organization_id = ? AND document_type = 'INVOICE'").param(org).query(String.class).single());
        String no = JsonPath.read(body, "$.invoiceNo");
        assertThat(period).isEqualTo("FY" + no.substring(0, 7));
    }

    @Test
    void issueNeedsKeyItemsDraftAndPermission() throws Exception {
        UUID empty = newDraft();
        mockMvc.perform(cashier.apply(post(INVOICES + "/" + empty + "/issue"))).andExpect(
                status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        issue(cashier, empty, "k-" + UUID.randomUUID()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
        UUID id = draftWithItem();
        issue(clerk, id, "k-" + UUID.randomUUID()).andExpect(status().isForbidden());
        issue(cashier, UUID.randomUUID(), "k-" + UUID.randomUUID()).andExpect(status().isNotFound());
        issue(cashier, id, "k-" + UUID.randomUUID()).andExpect(status().isOk());
        issue(cashier, id, "k-" + UUID.randomUUID()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVOICE_IMMUTABLE")));
    }

    @Test
    void twentyConcurrentIssuesGetGaplessUniqueNumbers() throws Exception {
        int threads = 20;
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            drafts.add(draftWithItem());
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> numbers = new ArrayList<>();
        try {
            List<Future<String>> results = new ArrayList<>();
            for (UUID draft : drafts) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = issue(cashier, draft, "c-" + draft).andReturn().getResponse();
                    assertThat(response.getStatus()).isEqualTo(200);
                    return (String) JsonPath.read(response.getContentAsString(), "$.invoiceNo");
                }));
            }
            for (Future<String> f : results) {
                numbers.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        List<String> sequence = numbers.stream().map(n -> n.substring(n.indexOf('/') + 1)).sorted().toList();
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= threads; i++) {
            expected.add(String.format("%06d", i));
        }
        assertThat(sequence).containsExactlyElementsOf(expected);
    }

    @Test
    void aRolledBackIssueDoesNotConsumeANumber() throws Exception {
        UUID first = draftWithItem();
        OrganizationPrincipal actor = principal(cashier.userId());
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            invoiceService.issue(actor, first, "rb-" + UUID.randomUUID(), "hash");
            throw new IllegalStateException("simulated failure after issue");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.invoice WHERE id = ?").param(first)
                .query(String.class).single())).isEqualTo("DRAFT");
        issue(cashier, first, "k-" + UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.invoiceNo", matchesPattern("\\d{4}-\\d{2}/000001")));
    }

    @Test
    void anIssuedInvoiceIsImmutableInTheApiAndTheDatabase() throws Exception {
        UUID id = draftWithItem();
        issue(cashier, id, "k-" + UUID.randomUUID()).andExpect(status().isOk());
        mockMvc.perform(cashier.apply(put(INVOICES + "/" + id + "/items")).contentType(JSON)
                .content("{\"version\":2,\"items\":[{\"description\":\"Synthetic charge\",\"quantity\":\"1\","
                        + "\"unitPrice\":\"10.00\"}]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVOICE_IMMUTABLE")));
        assertThat(sqlState("UPDATE hms.invoice_item SET description = 'changed' WHERE invoice_id = '" + id + "'"))
                .isEqualTo("HM004");
        assertThat(sqlState("DELETE FROM hms.invoice_item WHERE invoice_id = '" + id + "'")).isEqualTo("HM004");
        assertThat(sqlState("UPDATE hms.invoice SET total = 1 WHERE id = '" + id + "'")).isEqualTo("HM004");
        assertThat(sqlState("UPDATE hms.invoice SET invoice_no = 'X' WHERE id = '" + id + "'")).isEqualTo("HM004");
        assertThat(sqlState("UPDATE hms.invoice SET status = 'DRAFT', invoice_no = NULL, issued_at = NULL,"
                + " issued_by = NULL WHERE id = '" + id + "'")).isEqualTo("HM004");
        // Payment status, version and updated_at may still move.
        assertThat(sqlState("UPDATE hms.invoice SET status = 'PARTIALLY_PAID', version = version + 1,"
                + " updated_at = now() WHERE id = '" + id + "'")).isNull();
    }

    @Test
    void replayingAKeyReturnsTheSameResponseAndADifferentInvoiceIsKeyReuse() throws Exception {
        UUID id = draftWithItem();
        UUID other = draftWithItem();
        String key = "replay-" + UUID.randomUUID();
        String first = issue(cashier, id, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        String second = issue(cashier, id, key).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat((String) JsonPath.read(second, "$.invoiceNo")).isEqualTo(JsonPath.read(first, "$.invoiceNo"));
        assertThat((Integer) JsonPath.read(second, "$.version")).isEqualTo(JsonPath.read(first, "$.version"));
        assertThat(fx.auditActions(id).stream().filter("INVOICE_ISSUED"::equals)).hasSize(1);
        issue(cashier, other, key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT next_value FROM hms.document_sequence"
                + " WHERE organization_id = ? AND document_type = 'INVOICE'").param(org).query(Long.class).single()))
                .isEqualTo(2L);
        assertThat(sqlState("UPDATE hms.idempotency_record SET response_status = 500")).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void listFiltersBySeveralStatusesSeparatedByCommas() throws Exception {
        UUID draft = draftWithItem();
        UUID issued = draftWithItem();
        issue(cashier, issued, "k-" + UUID.randomUUID()).andExpect(status().isOk());
        String all = mockMvc.perform(cashier.apply(get(INVOICES)).param("status", "DRAFT,ISSUED"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(all, "$.items[*].id")).contains(draft.toString(),
                issued.toString());
        String onlyIssued = mockMvc.perform(cashier.apply(get(INVOICES)).param("status", "ISSUED,PARTIALLY_PAID"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(onlyIssued, "$.items[*].id")).contains(issued.toString())
                .doesNotContain(draft.toString());
        mockMvc.perform(cashier.apply(get(INVOICES)).param("status", "ISSUED,BOGUS"))
                .andExpect(status().isUnprocessableContent());
    }

    // --- helpers ---

    private ResultActions issue(AdminApiFixture.Session who, UUID id, String key) throws Exception {
        return mockMvc.perform(who.apply(post(INVOICES + "/" + id + "/issue")).header("Idempotency-Key", key));
    }

    private UUID newDraft() throws Exception {
        UUID encounter = insertEncounter();
        String body = mockMvc.perform(cashier.apply(post(INVOICES)).contentType(JSON).content(
                "{\"type\":\"OPD\",\"patientId\":\"" + patient + "\",\"encounterId\":\"" + encounter + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID draftWithItem() throws Exception {
        UUID id = newDraft();
        mockMvc.perform(cashier.apply(put(INVOICES + "/" + id + "/items")).contentType(JSON)
                .content("{\"version\":0,\"items\":[{\"description\":\"Synthetic charge\",\"quantity\":\"1\","
                        + "\"unitPrice\":\"100.00\",\"taxRate\":\"18\"}]}"))
                .andExpect(status().isOk());
        return id;
    }

    private OrganizationPrincipal principal(UUID userId) {
        UUID organization = org;
        return new OrganizationPrincipal() {
            @Override
            public UUID organizationId() {
                return organization;
            }

            @Override
            public UUID userId() {
                return userId;
            }
        };
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
                VALUES (?, ?, 'HMS-000201', 'Test Issue Patient', 'U', DATE '1990-01-01', '+910000000082', ?, now(),
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
                    + " VALUES (?, ?, ?, 'Dr. Test Issue', ?, ?, now(), now())")
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
