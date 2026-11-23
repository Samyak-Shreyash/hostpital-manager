package com.hms.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.InvoiceFlowFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/** [E9-3] Finance reports: daily revenue (cash basis) and outstanding payments. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class FinanceReportsIT extends PostgresIT {

    private static final String RANGE = "?from=2026-11-01&to=2026-11-05";
    private static final AtomicInteger SEQ = new AtomicInteger();

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
    private InvoiceFlowFixture flow;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "ACCOUNTANT_T", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "payment:create", "report:finance");
        accountant = fx.loggedIn(org, "accountant", "ACCOUNTANT_T");
        flow = new InvoiceFlowFixture(mockMvc, jdbc, fx, org, accountant);
        seedOtherOrganization();
    }

    @Test
    void revenueIsGroupedByLocalDayAndMode() throws Exception {
        UUID invoice = flow.issuedInvoice("2000.00");
        payment(org, invoice, "500.00", "CASH", "2026-11-01T10:00:00Z");
        payment(org, invoice, "250.00", "UPI", "2026-11-01T10:05:00Z");
        payment(org, invoice, "100.00", "CASH", "2026-11-01T18:45:00Z"); // 00:15 on 2 Nov in Asia/Kolkata

        mockMvc.perform(accountant.apply(get("/api/v1/reports/revenue/daily" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report", is("revenue-daily")))
                .andExpect(jsonPath("$.columns[4]", is("net")))
                .andExpect(jsonPath("$.rows.length()", is(3)))
                .andExpect(jsonPath("$.rows[0].date", is("2026-11-01")))
                .andExpect(jsonPath("$.rows[0].mode", is("CASH")))
                .andExpect(jsonPath("$.rows[0].collected", is("500.00")))
                .andExpect(jsonPath("$.rows[0].refunded", is("0.00")))
                .andExpect(jsonPath("$.rows[0].net", is("500.00")))
                .andExpect(jsonPath("$.rows[1].mode", is("UPI")))
                .andExpect(jsonPath("$.rows[1].net", is("250.00")))
                .andExpect(jsonPath("$.rows[2].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[2].net", is("100.00")));
        mockMvc.perform(accountant.apply(get("/api/v1/reports/revenue/daily?from=2026-11-03&to=2026-11-01")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void refundsCountOnTheDayTheyBecomeEffectiveAndTieOutWithReconciliation() throws Exception {
        UUID invoice = flow.issuedInvoice("2000.00");
        UUID paid = payment(org, invoice, "1000.00", "CASH", "2026-11-01T10:00:00Z");
        refund(org, paid, "100.00", "CASH", "RECORDED", "2026-11-02T05:00:00Z", null, null);
        refund(org, paid, "40.00", "UPI", "PENDING_APPROVAL", "2026-11-02T06:00:00Z", "APPROVED",
                "2026-11-03T05:00:00Z");
        refund(org, paid, "30.00", "CASH", "PENDING_APPROVAL", "2026-11-02T07:00:00Z", "REJECTED",
                "2026-11-02T08:00:00Z");
        refund(org, paid, "20.00", "CASH", "PENDING_APPROVAL", "2026-11-02T09:00:00Z", null, null); // still pending

        String body = mockMvc.perform(accountant.apply(get("/api/v1/reports/revenue/daily" + RANGE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()", is(3)))
                .andExpect(jsonPath("$.rows[0].net", is("1000.00")))
                .andExpect(jsonPath("$.rows[1].date", is("2026-11-02")))
                .andExpect(jsonPath("$.rows[1].mode", is("CASH")))
                .andExpect(jsonPath("$.rows[1].refunded", is("100.00")))
                .andExpect(jsonPath("$.rows[1].net", is("-100.00")))
                .andExpect(jsonPath("$.rows[2].date", is("2026-11-03")))
                .andExpect(jsonPath("$.rows[2].mode", is("UPI")))
                .andExpect(jsonPath("$.rows[2].net", is("-40.00")))
                .andReturn().getResponse().getContentAsString();

        BigDecimal reportNet = sum(body, "collected").subtract(sum(body, "refunded"));
        BigDecimal reconciledNet = fx.inOrg(org, () -> jdbc.sql("""
                SELECT coalesce(sum(paid - refunded), 0) FROM hms.invoice_reconciliation_v
                WHERE organization_id = ?""").param(org).query(BigDecimal.class).single());
        assertThat(reportNet).isEqualByComparingTo(reconciledNet).isEqualByComparingTo("860.00");

        String csv = mockMvc.perform(accountant.apply(get("/api/v1/reports/revenue/daily" + RANGE))
                        .accept("text/csv"))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains("2026-11-02,CASH,0.00,100.00,-100.00\r\n"); // money is never formula-prefixed
    }

    @Test
    void outstandingListsOnlyIssuedInvoicesWithAPositiveBalance() throws Exception {
        UUID partial = flow.issuedInvoice("1000.00");
        payment(org, partial, "400.00", "CASH", "2026-11-01T10:00:00Z");
        UUID full = flow.issuedInvoice("500.00");
        payment(org, full, "500.00", "CASH", "2026-11-01T10:00:00Z");
        UUID refundedFull = flow.issuedInvoice("300.00");
        UUID p = payment(org, refundedFull, "300.00", "CASH", "2026-11-01T10:00:00Z");
        refund(org, p, "50.00", "CASH", "RECORDED", "2026-11-02T05:00:00Z", null, null);
        flow.draftWithItem("700.00");

        String body = mockMvc.perform(accountant.apply(get("/api/v1/reports/payments/outstanding")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report", is("payments-outstanding")))
                .andExpect(jsonPath("$.from").doesNotExist())
                .andExpect(jsonPath("$.rows.length()", is(2)))
                .andExpect(jsonPath("$.rows[0].total", is("1000.00")))
                .andExpect(jsonPath("$.rows[0].credited", is("0.00")))
                .andExpect(jsonPath("$.rows[0].paid", is("400.00")))
                .andExpect(jsonPath("$.rows[0].refunded", is("0.00")))
                .andExpect(jsonPath("$.rows[0].balance", is("600.00")))
                .andExpect(jsonPath("$.rows[1].balance", is("50.00")))
                .andReturn().getResponse().getContentAsString();
        List<String> numbers = JsonPath.read(body, "$.rows[*].invoiceNo");
        assertThat(numbers).hasSize(2).doesNotContainNull();
        assertThat(JsonPath.<String>read(body, "$.rows[0].uhid")).isNotBlank();
        assertThat(JsonPath.<String>read(body, "$.rows[0].patientName")).isNotBlank();
    }

    @Test
    void anotherOrganizationsMoneyNeverAppears() throws Exception {
        // seedOtherOrganization() booked a CARD payment and refund and an unpaid invoice elsewhere.
        mockMvc.perform(accountant.apply(get("/api/v1/reports/revenue/daily" + RANGE)))
                .andExpect(jsonPath("$.rows.length()", is(0)));
        mockMvc.perform(accountant.apply(get("/api/v1/reports/payments/outstanding")))
                .andExpect(jsonPath("$.rows.length()", is(0)));
    }

    @Test
    void financePermissionIsRequired() throws Exception {
        fx.role(org, "CLINICAL_ONLY", "report:clinical");
        var clinical = fx.loggedIn(org, "clinical", "CLINICAL_ONLY");
        mockMvc.perform(clinical.apply(get("/api/v1/reports/revenue/daily" + RANGE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(clinical.apply(get("/api/v1/reports/payments/outstanding")))
                .andExpect(status().isForbidden());
    }

    private void seedOtherOrganization() throws Exception {
        UUID other = fx.organization();
        fx.role(other, "BILLER_O", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "payment:create");
        var biller = fx.loggedIn(other, "biller", "BILLER_O");
        var otherFlow = new InvoiceFlowFixture(mockMvc, jdbc, fx, other, biller);
        UUID invoice = otherFlow.issuedInvoice("2000.00");
        UUID paid = payment(other, invoice, "999.00", "CARD", "2026-11-02T10:00:00Z");
        refund(other, paid, "10.00", "CARD", "RECORDED", "2026-11-02T11:00:00Z", null, null);
        otherFlow.issuedInvoice("800.00");
    }

    private UUID payment(UUID organization, UUID invoice, String amount, String mode, String at) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.payment (id, organization_id, invoice_id, amount, mode, receipt_no, received_at,
                    received_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, (SELECT id FROM hms.app_user WHERE organization_id = ? LIMIT 1), now())""")
                .params(id, organization, invoice, new BigDecimal(amount), mode, number("RCT"),
                        Timestamp.from(Instant.parse(at)), organization).update());
        return id;
    }

    private UUID refund(UUID organization, UUID payment, String amount, String mode, String initial,
            String requestedAt, String decision, String decidedAt) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> {
            jdbc.sql("""
                    INSERT INTO hms.refund (id, organization_id, payment_id, amount, mode, reason, refund_no,
                        initial_status, requested_at, requested_by, created_at)
                    VALUES (?, ?, ?, ?, ?, 'Synthetic reason', ?, ?, ?,
                        (SELECT id FROM hms.app_user WHERE organization_id = ? LIMIT 1), now())""")
                    .params(id, organization, payment, new BigDecimal(amount), mode, number("RF"), initial,
                            Timestamp.from(Instant.parse(requestedAt)), organization).update();
            if (decision != null) {
                jdbc.sql("""
                        INSERT INTO hms.refund_decision (id, organization_id, refund_id, decision, reason,
                            decided_at, decided_by, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, (SELECT id FROM hms.app_user WHERE organization_id = ? LIMIT 1),
                            now())""")
                        .params(UUID.randomUUID(), organization, id, decision,
                                "REJECTED".equals(decision) ? "Synthetic reason" : null,
                                Timestamp.from(Instant.parse(decidedAt)), organization).update();
            }
            return null;
        });
        return id;
    }

    private static String number(String prefix) {
        return String.format("%s-2026-27/9%05d", prefix, SEQ.incrementAndGet());
    }

    private static BigDecimal sum(String body, String column) {
        List<String> values = JsonPath.read(body, "$.rows[*]." + column);
        return values.stream().map(BigDecimal::new).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
