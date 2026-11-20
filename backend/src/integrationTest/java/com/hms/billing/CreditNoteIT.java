package com.hms.billing;

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
import org.springframework.transaction.PlatformTransactionManager;

/** [E7-4] Credit notes. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class CreditNoteIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String CN_NO = "CN-\\d{4}-\\d{2}/\\d{6}";

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
    private AdminApiFixture.Session biller;
    private AdminApiFixture.Session clerk;
    private InvoiceFlowFixture flow;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "BILLER", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "invoice:credit", "payment:create");
        fx.role(org, "CLERK", "invoice:read", "payment:create");
        biller = fx.loggedIn(org, "biller", "BILLER");
        clerk = fx.loggedIn(org, "clerk", "CLERK");
        flow = new InvoiceFlowFixture(mockMvc, jdbc, fx, org, biller);
    }

    @Test
    void creditNotesUpToTheTotalCancelAnUnpaidInvoice() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        String first = credit(biller, invoice, "400.00", "Synthetic overcharge").andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditNoteNo", matchesPattern(CN_NO)))
                .andExpect(jsonPath("$.amount", is("400.00")))
                .andExpect(jsonPath("$.invoiceId", is(invoice.toString())))
                .andExpect(jsonPath("$.creditedTotal", is("400.00")))
                .andExpect(jsonPath("$.invoiceStatus", is("ISSUED")))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(first, "$.creditNoteNo")).endsWith("/000001");
        credit(biller, invoice, "600.01", "Synthetic too much").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("CREDIT_EXCEEDS_TOTAL")));
        credit(biller, invoice, "600.00", "Synthetic remainder").andExpect(status().isCreated())
                .andExpect(jsonPath("$.creditNoteNo", matchesPattern(CN_NO)))
                .andExpect(jsonPath("$.invoiceStatus", is("CANCELLED")));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("CANCELLED");
        credit(biller, invoice, "1.00", "Synthetic after cancel").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));

        UUID noteId = UUID.fromString(JsonPath.read(first, "$.id"));
        assertThat(fx.auditActions(noteId)).containsExactly("CREDIT_NOTE_ISSUED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(noteId).query(String.class).list();
        assertThat(rows).hasSize(1).noneMatch(r -> r.contains("Synthetic overcharge") || r.contains("400.00"));
        assertThat(flow.sqlState("UPDATE hms.credit_note SET amount = 1 WHERE id = '" + noteId + "'")).isNotNull();
        assertThat(flow.sqlState("DELETE FROM hms.credit_note WHERE id = '" + noteId + "'")).isNotNull();
    }

    @Test
    void aCreditNoteReducesWhatMayBePaid() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        credit(biller, invoice, "300.00", "Synthetic goodwill").andExpect(status().isCreated());
        flow.pay(biller, invoice, "700.01").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("EXCEEDS_OUTSTANDING")))
                .andExpect(jsonPath("$.detail", is("Outstanding is 700.00.")));
        flow.pay(biller, invoice, "700.00").andExpect(status().isCreated())
                .andExpect(jsonPath("$.outstanding", is("0.00")))
                .andExpect(jsonPath("$.invoiceStatus", is("PAID")));
    }

    @Test
    void aFullCreditOnAPaidInvoiceDoesNotCancelIt() throws Exception {
        UUID invoice = flow.issuedInvoice("1000.00");
        flow.paid(biller, invoice, "1000.00");
        credit(biller, invoice, "1000.00", "Synthetic full credit").andExpect(status().isCreated())
                .andExpect(jsonPath("$.invoiceStatus", is("PAID")));
        assertThat(flow.invoiceStatus(invoice)).isEqualTo("PAID");
    }

    @Test
    void stateValidationPermissionAndIsolation() throws Exception {
        UUID draft = flow.draftWithItem("100.00");
        credit(biller, draft, "10.00", "Synthetic").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        UUID invoice = flow.issuedInvoice("100.00");
        credit(biller, invoice, "0.00", "Synthetic").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        credit(biller, invoice, "10.00", " ").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        credit(clerk, invoice, "10.00", "Synthetic").andExpect(status().isForbidden());
        credit(biller, UUID.randomUUID(), "10.00", "Synthetic").andExpect(status().isNotFound());
        UUID otherOrg = fx.organization();
        fx.role(otherOrg, "BILLER", "invoice:credit");
        AdminApiFixture.Session outsider = fx.loggedIn(otherOrg, "biller", "BILLER");
        credit(outsider, invoice, "10.00", "Synthetic").andExpect(status().isNotFound());
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.credit_note").query(Integer.class)
                .single())).isZero();
    }

    private ResultActions credit(AdminApiFixture.Session who, UUID invoice, String amount, String reason)
            throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/invoices/" + invoice + "/credit-notes")).contentType(JSON)
                .content("{\"amount\":\"" + amount + "\",\"reason\":\"" + reason + "\"}"));
    }
}
