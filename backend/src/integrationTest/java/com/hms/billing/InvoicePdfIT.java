package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.InvoiceFlowFixture;
import com.hms.support.PdfText;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
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

/** [E7-5] Invoice and receipt PDFs. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class InvoicePdfIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String UUID_REGEX =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

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
    private AdminApiFixture.Session reader;
    private AdminApiFixture.Session nobody;
    private InvoiceFlowFixture flow;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "BILLER", "invoice:create", "invoice:read", "invoice:update", "invoice:issue",
                "invoice:price-override", "invoice:discount", "invoice:credit", "payment:create", "payment:read",
                "refund:create");
        fx.role(org, "READER", "invoice:read", "payment:read");
        fx.role(org, "NOBODY", "patient:read");
        biller = fx.loggedIn(org, "biller", "BILLER");
        reader = fx.loggedIn(org, "reader", "READER");
        nobody = fx.loggedIn(org, "nobody", "NOBODY");
        flow = new InvoiceFlowFixture(mockMvc, jdbc, fx, org, biller);
    }

    @Test
    void totalsMatchTheApiAndPaidAndOutstandingAreShown() throws Exception {
        UUID id = flow.draftWithItem("333.33", "18");
        String draft = getJson(biller, "/api/v1/invoices/" + id);
        mockMvc.perform(biller.apply(put("/api/v1/invoices/" + id + "/discount")).contentType(JSON)
                .content("{\"version\":" + JsonPath.<Integer>read(draft, "$.version")
                        + ",\"percent\":\"10\",\"reason\":\"Synthetic goodwill\"}")).andExpect(status().isOk());
        mockMvc.perform(biller.apply(post("/api/v1/invoices/" + id + "/issue"))
                .header("Idempotency-Key", "issue-" + UUID.randomUUID())).andExpect(status().isOk());
        flow.paid(biller, id, "100.00");
        UUID second = flow.paid(biller, id, "50.00");
        refund(second, "20.00");

        String json = getJson(reader, "/api/v1/invoices/" + id);
        BigDecimal total = new BigDecimal((String) JsonPath.read(json, "$.total"));
        assertThat(new BigDecimal((String) JsonPath.read(json, "$.discountTotal"))).isPositive();
        byte[] pdf = fetch(reader, "/api/v1/invoices/" + id + "/pdf").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        String text = PdfText.of(pdf);

        for (String field : List.of("subtotal", "discountTotal", "taxTotal", "total")) {
            assertThat(text).as(field).contains((String) JsonPath.read(json, "$." + field));
        }
        assertThat(text).contains((String) JsonPath.read(json, "$.invoiceNo"), "Test Refund Patient", "HMS-000401",
                "Synthetic charge", "Paid", "150.00", "Refunded", "20.00", "Outstanding",
                total.subtract(new BigDecimal("150.00")).add(new BigDecimal("20.00")).toPlainString());
        assertThat(text).doesNotContain("CANCELLED");
        assertThat(text).doesNotContainPattern(UUID_REGEX);

        List<String> audit = jdbc.sql("SELECT action || ' ' || array_to_string(changed_fields, ',')"
                + " FROM hms.audit_event WHERE entity_id = ? AND action = 'INVOICE_PDF_GENERATED'")
                .param(id).query(String.class).list();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).doesNotContain("Synthetic").doesNotContain("150.00");
    }

    @Test
    void aDraftHasNoPdfEvenWhenOnlyPdfIsAcceptedAndErrorsStayProblemJson() throws Exception {
        UUID draft = flow.draftWithItem("10.00");
        mockMvc.perform(reader.apply(get("/api/v1/invoices/" + draft + "/pdf")).accept(MediaType.APPLICATION_PDF))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        mockMvc.perform(reader.apply(get("/api/v1/invoices/" + UUID.randomUUID() + "/pdf"))
                .accept(MediaType.APPLICATION_PDF)).andExpect(status().isNotFound());
        fetch(nobody, "/api/v1/invoices/" + draft + "/pdf").andExpect(status().isForbidden());
    }

    @Test
    void aCancelledInvoiceIsMarked() throws Exception {
        UUID id = flow.issuedInvoice("200.00");
        mockMvc.perform(biller.apply(post("/api/v1/invoices/" + id + "/credit-notes")).contentType(JSON)
                .content("{\"amount\":\"200.00\",\"reason\":\"Synthetic cancel\"}")).andExpect(status().isCreated());
        byte[] pdf = fetch(reader, "/api/v1/invoices/" + id + "/pdf").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(PdfText.of(pdf)).contains("CANCELLED", "Credit notes", "200.00");
    }

    @Test
    void receiptShowsNumbersAmountModeAndReference() throws Exception {
        UUID invoice = flow.issuedInvoice("500.00");
        String body = mockMvc.perform(biller.apply(post("/api/v1/invoices/" + invoice + "/payments"))
                .header("Idempotency-Key", "pay-" + UUID.randomUUID()).contentType(JSON)
                .content("{\"amount\":\"125.50\",\"mode\":\"UPI\",\"referenceNo\":\"TEST-UPI-7788\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID payment = UUID.fromString(JsonPath.read(body, "$.id"));
        String invoiceNo = JsonPath.read(getJson(reader, "/api/v1/invoices/" + invoice), "$.invoiceNo");

        byte[] pdf = fetch(reader, "/api/v1/payments/" + payment + "/receipt").andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
        String text = PdfText.of(pdf);
        assertThat(text).contains((String) JsonPath.read(body, "$.receiptNo"), invoiceNo, "125.50", "UPI",
                "TEST-UPI-7788", "Test Refund Patient", "HMS-000401");
        assertThat(text).doesNotContainPattern(UUID_REGEX);
        assertThat(fx.auditActions(payment)).contains("RECEIPT_PDF_GENERATED");
        List<String> audit = jdbc.sql("SELECT array_to_string(changed_fields, ',') FROM hms.audit_event"
                + " WHERE entity_id = ? AND action = 'RECEIPT_PDF_GENERATED'").param(payment).query(String.class)
                .list();
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0)).doesNotContain("TEST-UPI-7788").doesNotContain("125.50");

        fetch(reader, "/api/v1/payments/" + UUID.randomUUID() + "/receipt").andExpect(status().isNotFound());
        fetch(nobody, "/api/v1/payments/" + payment + "/receipt").andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(get("/api/v1/payments/" + UUID.randomUUID() + "/receipt"))
                .accept(MediaType.APPLICATION_PDF)).andExpect(status().isNotFound());
    }

    private ResultActions fetch(AdminApiFixture.Session who, String url) throws Exception {
        return mockMvc.perform(who.apply(get(url)));
    }

    private String getJson(AdminApiFixture.Session who, String url) throws Exception {
        return fetch(who, url).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void refund(UUID payment, String amount) throws Exception {
        mockMvc.perform(biller.apply(post("/api/v1/payments/" + payment + "/refunds"))
                .header("Idempotency-Key", "rf-" + UUID.randomUUID()).contentType(JSON)
                .content("{\"amount\":\"" + amount + "\",\"mode\":\"CASH\",\"reason\":\"Synthetic\"}"))
                .andExpect(status().isCreated());
    }
}
