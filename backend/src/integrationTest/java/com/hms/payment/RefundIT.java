package com.hms.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.InvoiceFlowFixture;
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

/** [E7-4] Refunds with approval (threshold 1000.00 by default). Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class RefundIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String REFUND_NO = "RF-\\d{4}-\\d{2}/\\d{6}";

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
    private AdminApiFixture.Session accountant;
    private AdminApiFixture.Session supervisor;
    private AdminApiFixture.Session cashier;
    private InvoiceFlowFixture flow;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "ACCT", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "payment:create", "payment:read", "refund:create", "refund:approve");
        fx.role(org, "SUPERVISOR", "refund:approve");
        fx.role(org, "CASHIER", "payment:create", "refund:create");
        accountant = fx.loggedIn(org, "acct", "ACCT");
        supervisor = fx.loggedIn(org, "supervisor", "SUPERVISOR");
        cashier = fx.loggedIn(org, "cashier", "CASHIER");
        flow = new InvoiceFlowFixture(mockMvc, jdbc, fx, org, accountant);
    }

    @Test
    void refundUpToTheThresholdIsRecordedAndMovesTheInvoice() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        UUID payment = flow.paid(accountant, invoice, "1000.00");
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PAID");
        String first = refund(cashier, payment, "k-" + UUID.randomUUID(), "200.00", "Synthetic service not rendered")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("RECORDED")))
                .andExpect(jsonPath("$.amount", is("200.00")))
                .andExpect(jsonPath("$.paymentId", is(payment.toString())))
                .andExpect(jsonPath("$.invoiceId", is(invoice.toString())))
                .andExpect(jsonPath("$.refundNo", matchesPattern(REFUND_NO)))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.refundNo")).endsWith("/000001");
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PARTIALLY_PAID");
        refund(cashier, payment, "k-" + UUID.randomUUID(), "800.01", "Synthetic too much")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("REFUND_EXCEEDS_PAID")));
        refund(cashier, payment, "k-" + UUID.randomUUID(), "800.00", "Synthetic rest").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("RECORDED")));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("ISSUED");
        // The refunded amount is owed again.
        flow.pay(accountant, invoice, "1000.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceStatus", is("PAID")));

        UUID refundId = UUID.fromString(JsonPath.read(first, "$.id"));
        assertThat(fx.auditActions(refundId)).containsExactly("REFUND_REQUESTED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(refundId).query(String.class).list();
        assertThat(rows).hasSize(1).noneMatch(r -> r.contains("Synthetic service") || r.contains("200.00"));
        assertThat(flow.sqlState("UPDATE hms.refund SET amount = 1 WHERE id = '" + refundId + "'")).isNotNull();
        assertThat(flow.sqlState("DELETE FROM hms.refund WHERE id = '" + refundId + "'")).isNotNull();
    }

    @Test
    void refundAboveTheThresholdNeedsAnotherApprover() throws Exception {
        UUID invoice = flow.issuedInvoice("5000.00");
        UUID payment = flow.paid(accountant, invoice, "5000.00");
        String body = refund(accountant, payment, "k-" + UUID.randomUUID(), "1500.00", "Synthetic large refund")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("PENDING_APPROVAL")))
                .andReturn().getResponse().getContentAsString();
        UUID refundId = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PAID"); // pending refunds do not count

        approve(accountant, refundId).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SELF_APPROVAL_FORBIDDEN")));
        approve(cashier, refundId).andExpect(status().isForbidden());
        approve(supervisor, refundId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RECORDED")))
                .andExpect(jsonPath("$.decidedBy", is(supervisor.userId().toString())));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PARTIALLY_PAID");
        approve(supervisor, refundId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(fx.auditActions(refundId)).containsExactlyInAnyOrder("REFUND_REQUESTED", "REFUND_APPROVED");
    }

    @Test
    void rejectedRefundDoesNotCountAndFreesTheAmount() throws Exception {
        UUID invoice = flow.issuedInvoice("5000.00");
        UUID payment = flow.paid(accountant, invoice, "5000.00");
        String body = refund(accountant, payment, "k-" + UUID.randomUUID(), "5000.00", "Synthetic full refund")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID refundId = UUID.fromString(JsonPath.read(body, "$.id"));
        // Pending refunds reserve the amount.
        refund(accountant, payment, "k-" + UUID.randomUUID(), "1.00", "Synthetic extra")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("REFUND_EXCEEDS_PAID")));
        reject(supervisor, refundId, " ").andExpect(status().isUnprocessableContent());
        reject(accountant, refundId, "Synthetic decline").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("SELF_APPROVAL_FORBIDDEN")));
        reject(supervisor, refundId, "Synthetic decline reason").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJECTED")));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PAID");
        approve(supervisor, refundId).andExpect(status().isConflict());
        refund(cashier, payment, "k-" + UUID.randomUUID(), "100.00", "Synthetic after rejection")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status", is("RECORDED")));
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(refundId).query(String.class).list();
        assertThat(rows).hasSize(2).noneMatch(r -> r.contains("Synthetic decline"));
        assertThat(flow.sqlState("DELETE FROM hms.refund_decision WHERE refund_id = '" + refundId + "'"))
                .isNotNull();
    }

    @Test
    void concurrentRefundsCannotExceedThePayment() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        UUID payment = flow.paid(accountant, invoice, "1000.00");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = refund(cashier, payment, "c-" + UUID.randomUUID(), "600.00", "Synthetic race")
                            .andReturn().getResponse();
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
        assertThat(outcomes).containsExactlyInAnyOrder("201", "422 REFUND_EXCEEDS_PAID");
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.refund WHERE payment_id = ?")
                .param(payment).query(Integer.class).single())).isEqualTo(1);
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    void replayValidationAndIsolation() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        UUID payment = flow.paid(accountant, invoice, "1000.00");
        String key = "replay-" + UUID.randomUUID();
        String first = refund(cashier, payment, key, "10.00", "Synthetic replay").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = refund(cashier, payment, key, "10.00", "Synthetic replay").andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(second, "$.refundNo")).isEqualTo(JsonPath.read(first, "$.refundNo"));
        refund(cashier, payment, key, "20.00", "Synthetic replay").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

        mockMvc.perform(cashier.apply(post("/api/v1/payments/" + payment + "/refunds")).contentType(JSON)
                .content("{\"amount\":\"10.00\",\"mode\":\"CASH\",\"reason\":\"Synthetic\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        refund(cashier, payment, "k-" + UUID.randomUUID(), "0.00", "Synthetic zero")
                .andExpect(status().isUnprocessableContent());
        refund(cashier, payment, "k-" + UUID.randomUUID(), "10.00", "")
                .andExpect(status().isUnprocessableContent());
        refund(cashier, UUID.randomUUID(), "k-" + UUID.randomUUID(), "10.00", "Synthetic")
                .andExpect(status().isNotFound());
        approve(supervisor, UUID.randomUUID()).andExpect(status().isNotFound());

        UUID otherOrg = fx.organization();
        fx.role(otherOrg, "CASHIER", "refund:create", "refund:approve");
        AdminApiFixture.Session outsider = fx.loggedIn(otherOrg, "cashier", "CASHIER");
        refund(outsider, payment, "k-" + UUID.randomUUID(), "10.00", "Synthetic").andExpect(status().isNotFound());
        approve(outsider, UUID.fromString(JsonPath.read(first, "$.id"))).andExpect(status().isNotFound());
        refund(supervisor, payment, "k-" + UUID.randomUUID(), "10.00", "Synthetic").andExpect(status().isForbidden());
    }

    // --- helpers ---

    private ResultActions refund(AdminApiFixture.Session who, UUID payment, String key, String amount,
            String reason) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/payments/" + payment + "/refunds"))
                .header("Idempotency-Key", key).contentType(JSON)
                .content("{\"amount\":\"" + amount + "\",\"mode\":\"CASH\",\"reason\":\"" + reason + "\"}"));
    }

    private ResultActions approve(AdminApiFixture.Session who, UUID refund) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/refunds/" + refund + "/approve")));
    }

    private ResultActions reject(AdminApiFixture.Session who, UUID refund, String reason) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/refunds/" + refund + "/reject")).contentType(JSON)
                .content("{\"reason\":\"" + reason + "\"}"));
    }
}
