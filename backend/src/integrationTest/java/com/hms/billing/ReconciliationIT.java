package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.billing.application.ReconciliationJob;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.InvoiceFlowFixture;
import com.hms.support.PostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
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
import org.springframework.transaction.PlatformTransactionManager;

/** [E7-7] Nightly reconciliation. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class ReconciliationIT extends PostgresIT {

    private static final String COUNTER = "hms.billing.reconciliation.mismatch";

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
    ReconciliationJob job;

    @Autowired
    MeterRegistry meters;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session biller;
    private InvoiceFlowFixture flow;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "BILLER", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "invoice:credit", "payment:create", "refund:create");
        biller = fx.loggedIn(org, "biller", "BILLER");
        flow = new InvoiceFlowFixture(mockMvc, jdbc, fx, org, biller);
    }

    @Test
    void consistentDataIncludingStatesTheAppProducesIsNotFlagged() throws Exception {
        UUID partial = flow.issuedInvoice("1000.00");
        flow.paid(biller, partial, "400.00");
        UUID credited = flow.issuedInvoice("1000.00");
        flow.paid(biller, credited, "400.00");
        credit(credited, "600.00"); // the remainder is credited: stays PARTIALLY_PAID, which is not a mismatch
        UUID fullyCredited = flow.issuedInvoice("500.00");
        flow.paid(biller, fullyCredited, "500.00");
        credit(fullyCredited, "500.00"); // stays PAID with net paid above total - credited
        UUID cancelled = flow.issuedInvoice("200.00");
        credit(cancelled, "200.00");
        UUID refunded = flow.issuedInvoice("300.00");
        UUID payment = flow.paid(biller, refunded, "300.00");
        refund(payment, "50.00");
        flow.draftWithItem("100.00");
        assertThat(flow.invoiceStatus(credited)).isEqualTo("PARTIALLY_PAID");
        assertThat(flow.invoiceStatus(refunded)).isEqualTo("PARTIALLY_PAID");

        double before = counter();
        assertThat(job.reconcile(org)).isZero();
        assertThat(counter() - before).isZero();
    }

    @Test
    void aStatusThatDisagreesWithTheMoneyIsFlaggedOnceWithAuditAndMetric() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        UUID fine = flow.issuedInvoice("1000.00");
        flow.paid(biller, fine, "1000.00");
        double before = counter();
        ownerExec("ALTER TABLE hms.invoice DISABLE TRIGGER tg_invoice_guard");
        try {
            ownerExec("UPDATE hms.invoice SET status = 'PAID' WHERE id = '" + invoice + "'");
            assertThat(job.reconcile(org)).isEqualTo(1);
        } finally {
            ownerExec("UPDATE hms.invoice SET status = 'ISSUED' WHERE id = '" + invoice + "'");
            ownerExec("ALTER TABLE hms.invoice ENABLE TRIGGER tg_invoice_guard");
        }
        assertThat(counter() - before).isEqualTo(1.0);
        List<String> audit = fx.inOrg(org, () -> jdbc.sql("""
                SELECT action || '|' || entity_type || '|' || coalesce(actor_id::text, 'system') || '|'
                    || array_to_string(changed_fields, ',')
                FROM hms.audit_event WHERE entity_id = ? AND action = 'RECONCILIATION_MISMATCH'""").param(invoice).query(String.class).list());
        assertThat(audit).containsExactly("RECONCILIATION_MISMATCH|INVOICE|system|status");
        assertThat(job.reconcile(org)).isZero(); // restored
        assertThat(ownerTriggerEnabled()).isTrue();
    }

    @Test
    void netPaidAboveTheTotalIsFlagged() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        flow.paid(biller, invoice, "1000.00");
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.payment (id, organization_id, invoice_id, amount, mode, receipt_no, received_at,
                    received_by, created_at)
                VALUES (?, ?, ?, 1.00, 'CASH', 'RCT-2026-27/990001', now(), ?, now())""")
                .params(UUID.randomUUID(), org, invoice, biller.userId()).update());
        assertThat(job.reconcile(org)).isEqualTo(1);
        assertThat(fx.auditActions(invoice)).contains("RECONCILIATION_MISMATCH");
    }

    @Test
    void theViewIsOrganizationScopedAndTheJobCoversEveryOrganization() throws Exception {
        UUID invoice = flow.issuedInvoice("100.00");
        UUID other = fx.organization();
        Integer visible = fx.inOrg(other, () -> jdbc.sql(
                "SELECT count(*) FROM hms.invoice_reconciliation_v WHERE id = ?").param(invoice)
                .query(Integer.class).single());
        assertThat(visible).isZero();
        Integer own = fx.inOrg(org, () -> jdbc.sql(
                "SELECT count(*) FROM hms.invoice_reconciliation_v WHERE id = ?").param(invoice)
                .query(Integer.class).single());
        assertThat(own).isEqualTo(1);
        assertThat(job.reconcile(other)).isZero();
        assertThat(job.run()).isGreaterThanOrEqualTo(0); // iterates all organizations without a session
    }

    @Test
    void theCounterIsExportedUnderTheDocumentedPrometheusName() throws Exception {
        counter(); // the job registers the counter at startup, so it is exported before the first mismatch
        String scrape = mockMvc.perform(get("/actuator/prometheus")).andReturn().getResponse().getContentAsString();
        assertThat(scrape).contains("hms_billing_reconciliation_mismatch_total");
    }

    private double counter() {
        return meters.counter(COUNTER).count();
    }

    private void credit(UUID invoice, String amount) throws Exception {
        mockMvc.perform(biller.apply(post("/api/v1/invoices/" + invoice + "/credit-notes"))
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .content("{\"amount\":\"" + amount + "\",\"reason\":\"Synthetic\"}"))
                .andExpect(status().isCreated());
    }

    private void refund(UUID payment, String amount) throws Exception {
        mockMvc.perform(biller.apply(post("/api/v1/payments/" + payment + "/refunds"))
                .header("Idempotency-Key", "rf-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON_VALUE)
                .content("{\"amount\":\"" + amount + "\",\"mode\":\"CASH\",\"reason\":\"Synthetic\"}"))
                .andExpect(status().isCreated());
    }

    /** One statement on a direct hms_owner connection (the migration role, also bound by RLS) in the org. */
    private void ownerExec(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER, OWNER_PASSWORD)) {
            try (PreparedStatement ctx = c.prepareStatement("SELECT set_config('app.organization_id', ?, false)")) {
                ctx.setString(1, org.toString());
                ctx.execute();
            }
            try (PreparedStatement st = c.prepareStatement(sql)) {
                st.executeUpdate();
            }
        }
    }

    private boolean ownerTriggerEnabled() throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER, OWNER_PASSWORD);
                PreparedStatement st = c.prepareStatement(
                        "SELECT bool_and(tgenabled = 'O') FROM pg_trigger WHERE tgname = 'tg_invoice_guard'");
                var rs = st.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
