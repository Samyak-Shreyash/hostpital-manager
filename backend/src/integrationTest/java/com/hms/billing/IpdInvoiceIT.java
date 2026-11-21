package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E8-6] IPD interim and final invoices built from the admission's room-days through {@code ChargeSource} (ruling
 * R4). Stays are back-dated on a direct hms_owner connection; DISCHARGE_INITIATED and the planned discharge are set
 * by SQL until the discharge summary endpoint exists ([E8-7]). Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IpdInvoiceIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

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
    private AdminApiFixture.Session clerk;
    private IpdFixture ipd;
    private UUID ward;
    private UUID doctor;
    private int beds;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("IPD");
        doctor = ipd.doctor();
        price(ipd.rateService(), "1500.00");
    }

    /** {@code daysAgo} days before today (org time zone) at {@code hour}:00 IST. */
    private static Instant daysAgo(int daysAgo, int hour) {
        return LocalDate.now(IST).minusDays(daysAgo).atTime(hour, 0).atZone(IST).toInstant();
    }

    private void price(UUID service, String amount) throws SQLException {
        owner("INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, created_by,"
                + " created_at) VALUES ('" + UUID.randomUUID() + "', '" + org + "', '" + service + "', " + amount
                + ", '2020-01-01T00:00:00Z', '" + clerk.userId() + "', now())");
    }

    private UUID service(String code, String amount) throws Exception {
        String body = mockMvc.perform(clerk.apply(post("/api/v1/services")).contentType(JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Room " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        price(id, amount);
        return id;
    }

    private UUID bed(String bedNo, UUID rateService) throws Exception {
        String body = mockMvc.perform(clerk.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content("{\"bedNo\":\"" + bedNo + "\",\"dailyRateServiceId\":\"" + rateService + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Back-dates the admission and its assignments; {@code times[i]} is the start of assignment i (oldest first). */
    private void backdate(UUID admission, Instant... times) throws SQLException {
        owner("UPDATE hms.admission SET admitted_at = '" + times[0] + "' WHERE id = '" + admission + "'");
        List<UUID> ids = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.bed_assignment WHERE admission_id = ?"
                + " ORDER BY assigned_at").param(admission).query(UUID.class).list());
        for (int i = 0; i < ids.size(); i++) {
            String released = i + 1 < ids.size() ? "'" + times[i + 1] + "'" : "released_at";
            owner("UPDATE hms.bed_assignment SET assigned_at = '" + times[i] + "', released_at = " + released
                    + " WHERE id = '" + ids.get(i) + "'");
        }
    }

    /** Stands in for PUT /discharge-summary ([E8-7]): DISCHARGE_INITIATED with a planned discharge instant. */
    private void dischargeInitiated(UUID admission, Instant planned) throws SQLException {
        owner("UPDATE hms.admission SET status = 'DISCHARGE_INITIATED', planned_discharge_at = '" + planned
                + "' WHERE id = '" + admission + "'");
    }

    private void owner(String sql) throws SQLException {
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

    private UUID patientOf(UUID admission) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT patient_id FROM hms.admission WHERE id = ?").param(admission)
                .query(UUID.class).single());
    }

    private ResultActions draft(UUID admission, String kind) throws Exception {
        return mockMvc.perform(clerk.apply(post("/api/v1/invoices")).contentType(JSON)
                .content("{\"type\":\"IPD\",\"patientId\":\"" + patientOf(admission) + "\",\"admissionId\":\""
                        + admission + "\",\"kind\":\"" + kind + "\"}"));
    }

    private String created(UUID admission, String kind) throws Exception {
        return draft(admission, kind).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions discard(String invoice) throws Exception {
        return mockMvc.perform(clerk.apply(post("/api/v1/invoices/" + JsonPath.read(invoice, "$.id") + "/discard"))
                .contentType(JSON).content("{\"version\":" + JsonPath.read(invoice, "$.version") + "}"));
    }

    private ResultActions issue(String invoice) throws Exception {
        return mockMvc.perform(clerk.apply(post("/api/v1/invoices/" + JsonPath.read(invoice, "$.id") + "/issue"))
                .header("Idempotency-Key", "key-" + UUID.randomUUID()));
    }

    private String roomTotal(UUID admission, Instant asOf) throws Exception {
        String body = mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + admission + "/charges-preview"))
                .param("asOf", asOf.toString())).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.roomTotal");
    }

    private UUID admitted(String bedNo) throws Exception {
        return ipd.admitted(clerk, ipd.patient(), doctor, ipd.bed(ward, bedNo));
    }

    private static BigDecimal money(String invoice) {
        return new BigDecimal((String) JsonPath.read(invoice, "$.total"));
    }

    private static List<LocalDate> roomDates(String invoice) {
        List<Map<String, Object>> items = JsonPath.read(invoice, "$.items[?(@.kind == 'ROOM')]");
        List<LocalDate> dates = new ArrayList<>();
        String through = JsonPath.read(invoice, "$.roomChargesThrough");
        LocalDate end = LocalDate.parse(through);
        int days = items.stream().mapToInt(i -> new BigDecimal((String) i.get("quantity")).intValueExact()).sum();
        for (int i = days - 1; i >= 0; i--) {
            dates.add(end.minusDays(i));
        }
        return dates;
    }

    @Test
    void finalTotalsEqualPreview() throws Exception {
        UUID general = ipd.rateService();
        UUID privateRoom = service("PVT_ROOM", "2500.00");
        UUID a = bed("G-1", general);
        UUID b = bed("P-1", privateRoom);
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/bed-assignments/transfer"))
                .contentType(JSON).content("{\"toBedId\":\"" + b + "\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isOk());
        backdate(id, daysAgo(5, 10), daysAgo(3, 15));
        Instant planned = daysAgo(0, 9).plus(1, ChronoUnit.DAYS);
        dischargeInitiated(id, planned);

        String invoice = created(id, "FINAL");
        assertThat(JsonPath.<String>read(invoice, "$.type")).isEqualTo("IPD");
        assertThat(JsonPath.<String>read(invoice, "$.kind")).isEqualTo("FINAL");
        assertThat(JsonPath.<String>read(invoice, "$.status")).isEqualTo("DRAFT");
        assertThat(JsonPath.<String>read(invoice, "$.roomChargesThrough")).isEqualTo(LocalDate.now(IST).toString());
        assertThat(JsonPath.<String>read(invoice, "$.items[0].kind")).isEqualTo("ROOM");
        assertThat(JsonPath.<String>read(invoice, "$.items[0].serviceId")).isEqualTo(general.toString());
        assertThat(JsonPath.<String>read(invoice, "$.items[0].unitPrice")).isEqualTo("1500.00");
        assertThat(JsonPath.<String>read(invoice, "$.items[1].unitPrice")).isEqualTo("2500.00");
        assertThat(JsonPath.<String>read(invoice, "$.total")).isEqualTo(roomTotal(id, planned));
        assertThat(fx.auditActions(UUID.fromString(JsonPath.read(invoice, "$.id")))).contains("INVOICE_CREATED");

        issue(invoice).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("ISSUED")))
                .andExpect(jsonPath("$.kind", is("FINAL")))
                .andExpect(jsonPath("$.invoiceNo", matchesPattern("\\d{4}-\\d{2}/\\d{6}")));
    }

    @Test
    void interimThenFinalCoverEachDayOnce() throws Exception {
        UUID id = admitted("I-1");
        backdate(id, daysAgo(6, 10));
        String interim = created(id, "INTERIM");
        assertThat(JsonPath.<String>read(interim, "$.roomChargesThrough"))
                .isEqualTo(LocalDate.now(IST).minusDays(1).toString());
        issue(interim).andExpect(status().isOk());

        // A second interim on the same day has nothing to charge.
        draft(id, "INTERIM").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));

        Instant planned = daysAgo(0, 9).plus(2, ChronoUnit.DAYS);
        dischargeInitiated(id, planned);
        String fin = created(id, "FINAL");
        List<LocalDate> first = roomDates(interim);
        List<LocalDate> second = roomDates(fin);
        assertThat(second.getFirst()).isEqualTo(first.getLast().plusDays(1));
        assertThat(first).doesNotContainAnyElementsOf(second);
        assertThat(money(interim).add(money(fin))).isEqualByComparingTo(roomTotal(id, planned));
    }

    @Test
    void discardedInterimDaysAreRebilled() throws Exception {
        UUID id = admitted("D-1");
        backdate(id, daysAgo(4, 10));
        String interim = created(id, "INTERIM");
        discard(interim).andExpect(status().isOk());
        String again = created(id, "INTERIM");
        assertThat(roomDates(again)).isEqualTo(roomDates(interim));
        assertThat(money(again)).isEqualByComparingTo("6000.00");
    }

    /** Two interims: the first trimmed (by SQL, as a DRAFT) to end 3 days ago, the second covering the rest. */
    private String[] twoInterims(String bedNo) throws Exception {
        UUID id = admitted(bedNo);
        backdate(id, daysAgo(6, 10));
        String first = created(id, "INTERIM");
        owner("UPDATE hms.invoice SET room_charges_through = '" + LocalDate.now(IST).minusDays(4)
                + "' WHERE id = '" + JsonPath.read(first, "$.id") + "'");
        String second = created(id, "INTERIM");
        assertThat(roomDates(second).getFirst()).isEqualTo(LocalDate.now(IST).minusDays(3));
        return new String[] {reload(first), second};
    }

    private String reload(String invoice) throws Exception {
        return mockMvc.perform(clerk.apply(get("/api/v1/invoices/" + JsonPath.read(invoice, "$.id"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void discardingAnEarlierInterimIs409() throws Exception {
        String[] interims = twoInterims("E-1");
        discard(interims[0]).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        discard(interims[1]).andExpect(status().isOk());
        discard(interims[0]).andExpect(status().isOk());
    }

    @Test
    void fullyCreditingAnEarlierInterimIs409ButPartialCreditWorks() throws Exception {
        String[] interims = twoInterims("E-2");
        String first = issue(interims[0]).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        issue(interims[1]).andExpect(status().isOk());
        Object firstId = JsonPath.read(first, "$.id");
        String total = JsonPath.read(first, "$.total");
        mockMvc.perform(clerk.apply(post("/api/v1/invoices/" + firstId + "/credit-notes")).contentType(JSON)
                        .content("{\"amount\":\"" + total + "\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        mockMvc.perform(clerk.apply(post("/api/v1/invoices/" + firstId + "/credit-notes")).contentType(JSON)
                        .content("{\"amount\":\"100.00\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void secondLiveFinalIs409() throws Exception {
        UUID id = admitted("F-1");
        backdate(id, daysAgo(3, 10));
        dischargeInitiated(id, daysAgo(0, 23));
        String fin = created(id, "FINAL");
        draft(id, "FINAL").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_FINAL_INVOICE")));
        discard(fin).andExpect(status().isOk());
        created(id, "FINAL");
    }

    @Test
    void stateAndInputRules() throws Exception {
        UUID id = admitted("S-1");
        backdate(id, daysAgo(3, 10));
        // FINAL needs DISCHARGE_INITIATED.
        draft(id, "FINAL").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        // Another patient: 422; unknown admission: 404; bad kind: 422.
        mockMvc.perform(clerk.apply(post("/api/v1/invoices")).contentType(JSON)
                .content("{\"type\":\"IPD\",\"patientId\":\"" + ipd.patient() + "\",\"admissionId\":\"" + id
                        + "\",\"kind\":\"INTERIM\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(clerk.apply(post("/api/v1/invoices")).contentType(JSON)
                .content("{\"type\":\"IPD\",\"patientId\":\"" + patientOf(id) + "\",\"admissionId\":\""
                        + UUID.randomUUID() + "\",\"kind\":\"INTERIM\"}")).andExpect(status().isNotFound());
        draft(id, "OTHER").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // Another organization's admission is 404.
        UUID other = fx.organization();
        AdminApiFixture.Session outsider = fx.withAllPermissions(other);
        mockMvc.perform(outsider.apply(post("/api/v1/invoices")).contentType(JSON)
                .content("{\"type\":\"IPD\",\"patientId\":\"" + patientOf(id) + "\",\"admissionId\":\"" + id
                        + "\",\"kind\":\"INTERIM\"}")).andExpect(status().isNotFound());
        // A cancelled admission cannot be invoiced.
        String admission = mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + id))).andReturn().getResponse()
                .getContentAsString();
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":" + JsonPath.read(admission, "$.version") + "}")).andExpect(status().isOk());
        draft(id, "INTERIM").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void roomLinesSurviveItemReplaceAndCannotBeChanged() throws Exception {
        UUID id = admitted("R-1");
        backdate(id, daysAgo(3, 10));
        String interim = created(id, "INTERIM");
        Object invoiceId = JsonPath.read(interim, "$.id");
        String room = "{\"serviceId\":\"" + ipd.rateService() + "\",\"description\":\""
                + JsonPath.read(interim, "$.items[0].description") + "\",\"quantity\":\"3\",\"unitPrice\":\"1500.00\"}";
        String extra = "{\"description\":\"Synthetic dressing\",\"quantity\":\"1\",\"unitPrice\":\"200.00\"}";
        // Room line kept, one non-room line added.
        String body = mockMvc.perform(clerk.apply(put("/api/v1/invoices/" + invoiceId + "/items")).contentType(JSON)
                        .content("{\"version\":" + JsonPath.read(interim, "$.version") + ",\"items\":[" + room + ","
                                + extra + "]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].kind", is("ROOM")))
                .andExpect(jsonPath("$.items[1].kind", is("STANDARD")))
                .andExpect(jsonPath("$.total", is("4700.00")))
                .andReturn().getResponse().getContentAsString();
        long version = ((Number) JsonPath.read(body, "$.version")).longValue();
        // Dropping the room line is refused.
        mockMvc.perform(clerk.apply(put("/api/v1/invoices/" + invoiceId + "/items")).contentType(JSON)
                        .content("{\"version\":" + version + ",\"items\":[" + extra + "]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // Changing it (quantity) is refused.
        mockMvc.perform(clerk.apply(put("/api/v1/invoices/" + invoiceId + "/items")).contentType(JSON)
                        .content("{\"version\":" + version + ",\"items\":[" + room.replace("\"3\"", "\"2\"") + "]}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void twoConcurrentInterimDraftsDoNotOverlap() throws Exception {
        UUID id = admitted("C-1");
        backdate(id, daysAgo(5, 10));
        UUID patient = patientOf(id);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> call = () -> {
            start.await();
            return mockMvc.perform(clerk.apply(post("/api/v1/invoices")).contentType(JSON)
                    .content("{\"type\":\"IPD\",\"patientId\":\"" + patient + "\",\"admissionId\":\"" + id
                            + "\",\"kind\":\"INTERIM\"}")).andReturn();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> one = pool.submit(call);
            Future<MvcResult> two = pool.submit(call);
            start.countDown();
            List<MvcResult> results = List.of(one.get(), two.get());
            List<String> createdBodies = new ArrayList<>();
            for (MvcResult r : results) {
                int code = r.getResponse().getStatus();
                if (code == 201) {
                    createdBodies.add(r.getResponse().getContentAsString());
                }
            }
            // The advisory lock serialises the drafts: the second sees the first's coverage and has nothing to charge.
            assertThat(createdBodies).hasSize(1);
            assertThat(results).filteredOn(r -> r.getResponse().getStatus() == 422).hasSize(1);
            List<LocalDate> all = new ArrayList<>();
            createdBodies.forEach(b -> all.addAll(roomDates(b)));
            assertThat(all).doesNotHaveDuplicates();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void cancelAdmissionWithLiveIpdInvoiceIs409() throws Exception {
        UUID id = admitted("X-1");
        backdate(id, daysAgo(2, 10));
        String interim = created(id, "INTERIM");
        String admission = mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + id))).andReturn().getResponse()
                .getContentAsString();
        Object version = JsonPath.read(admission, "$.version");
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/cancel")).contentType(JSON)
                        .content("{\"version\":" + version + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        // Once the draft is discarded the admission can be cancelled.
        discard(interim).andExpect(status().isOk());
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":" + version + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Test
    void listShowsKind() throws Exception {
        String body = mockMvc.perform(clerk.apply(get("/api/v1/invoices"))).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        assertThat(body).isNotNull();
        UUID id = admitted("O-1");
        backdate(id, daysAgo(2, 10));
        created(id, "INTERIM");
        mockMvc.perform(clerk.apply(get("/api/v1/invoices")).param("patientId", patientOf(id).toString()))
                .andExpect(jsonPath("$.items[0].kind", is("INTERIM")))
                .andExpect(jsonPath("$.items[0].encounterId", nullValue()));
    }
}
