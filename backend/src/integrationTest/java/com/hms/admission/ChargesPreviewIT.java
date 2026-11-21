package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.billing.api.ChargeSource;
import com.hms.billing.api.ChargeSource.ChargeLine;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

/**
 * [E8-5] Room-day charges (BR-B6 rule [A]) and {@code GET /admissions/{id}/charges-preview}. The organization's time
 * zone is the default Asia/Kolkata; stays are back-dated on a direct hms_owner connection. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChargesPreviewIT extends PostgresIT {

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

    @Autowired
    ChargeSource chargeSource;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session clerk;
    private IpdFixture ipd;
    private UUID ward;
    private UUID doctor;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("CHG");
        doctor = ipd.doctor();
    }

    private static String ist(int day, int hour) {
        return LocalDateTime.of(2026, 11, day, hour, 0).atZone(IST).toInstant().toString();
    }

    private UUID service(String code) throws Exception {
        String body = mockMvc.perform(clerk.apply(post("/api/v1/services")).contentType(JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Room " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID bed(String bedNo, UUID rateService) throws Exception {
        String body = mockMvc.perform(clerk.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content("{\"bedNo\":\"" + bedNo + "\",\"dailyRateServiceId\":\"" + rateService + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** A price version {@code [from, to)}; {@code to} null is open-ended. */
    private void price(UUID service, String amount, String from, String to) throws SQLException {
        owner("INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, effective_to,"
                + " created_by, created_at) VALUES ('" + UUID.randomUUID() + "', '" + org + "', '" + service + "', "
                + amount + ", '" + from + "', " + (to == null ? "NULL" : "'" + to + "'") + ", '" + clerk.userId()
                + "', now())");
    }

    /** Back-dates the admission and its assignments; {@code times[i]} is the start of assignment i (oldest first). */
    private void backdate(UUID admission, String... times) throws SQLException {
        owner("UPDATE hms.admission SET admitted_at = '" + times[0] + "' WHERE id = '" + admission + "'");
        List<UUID> ids = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.bed_assignment WHERE admission_id = ?"
                + " ORDER BY assigned_at").param(admission).query(UUID.class).list());
        for (int i = 0; i < ids.size(); i++) {
            String released = i + 1 < ids.size() ? "'" + times[i + 1] + "'" : "released_at";
            owner("UPDATE hms.bed_assignment SET assigned_at = '" + times[i] + "', released_at = " + released
                    + " WHERE id = '" + ids.get(i) + "'");
        }
    }

    /** One statement on a direct hms_owner connection (also bound by RLS) in the org. */
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

    private ResultActions preview(AdminApiFixture.Session who, UUID admission, String asOf) throws Exception {
        return mockMvc.perform(who.apply(get("/api/v1/admissions/" + admission + "/charges-preview"))
                .param("asOf", asOf));
    }

    private UUID transferred(UUID from, UUID to) throws Exception {
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, from);
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/bed-assignments/transfer"))
                .contentType(JSON).content("{\"toBedId\":\"" + to + "\",\"reason\":\"Synthetic reason\"}"))
                .andExpect(status().isOk());
        return id;
    }

    @Test
    void previewAfterTransfer() throws Exception {
        UUID general = service("GEN1");
        UUID privateRoom = service("PVT1");
        price(general, "1500.00", "2026-01-01T00:00:00Z", null);
        price(privateRoom, "2500.00", "2026-01-01T00:00:00Z", null);
        UUID a = bed("G-1", general);
        UUID b = bed("P-1", privateRoom);
        UUID id = transferred(a, b);
        backdate(id, ist(21, 10), ist(22, 15));

        preview(clerk, id, ist(24, 9)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].description", is("Room charges — Ward CHG G-1 (2026-11-21–2026-11-21)")))
                .andExpect(jsonPath("$.lines[0].quantity", is(1)))
                .andExpect(jsonPath("$.lines[0].unitPrice", is("1500.00")))
                .andExpect(jsonPath("$.lines[0].amount", is("1500.00")))
                .andExpect(jsonPath("$.lines[0].from", is("2026-11-21")))
                .andExpect(jsonPath("$.lines[1].quantity", is(2)))
                .andExpect(jsonPath("$.lines[1].unitPrice", is("2500.00")))
                .andExpect(jsonPath("$.lines[1].amount", is("5000.00")))
                .andExpect(jsonPath("$.lines[1].from", is("2026-11-22")))
                .andExpect(jsonPath("$.lines[1].to", is("2026-11-23")))
                .andExpect(jsonPath("$.roomTotal", is("6500.00")));
        assertThat(fx.auditActions(id)).contains("ADMISSION_VIEWED");
    }

    @Test
    void priceChangeMidStaySplitsTheLineAndAfterDateSkipsDays() throws Exception {
        UUID general = service("GEN2");
        price(general, "1500.00", "2026-01-01T00:00:00Z", ist(23, 0));
        price(general, "1800.00", ist(23, 0), null);
        UUID a = bed("G-2", general);
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        backdate(id, ist(21, 10));

        preview(clerk, id, ist(25, 9)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].quantity", is(2)))
                .andExpect(jsonPath("$.lines[0].unitPrice", is("1500.00")))
                .andExpect(jsonPath("$.lines[1].quantity", is(2)))
                .andExpect(jsonPath("$.lines[1].unitPrice", is("1800.00")))
                .andExpect(jsonPath("$.lines[1].from", is("2026-11-23")))
                .andExpect(jsonPath("$.roomTotal", is("6600.00")));

        List<ChargeLine> after = fx.inOrg(org, () -> chargeSource.roomCharges(org, id, LocalDate.of(2026, 11, 23),
                java.time.Instant.parse(ist(25, 9))));
        assertThat(after).singleElement().satisfies(l -> {
            assertThat(l.from()).isEqualTo(LocalDate.of(2026, 11, 24));
            assertThat(l.quantity()).isEqualTo(1);
            assertThat(l.serviceId()).isEqualTo(general);
        });
        assertThat(fx.inOrg(org, () -> chargeSource.admission(org, id)).orElseThrow().chargeEndAt()).isNull();
    }

    @Test
    void dateWithoutPriceIs422NamingTheBed() throws Exception {
        UUID general = service("GEN3");
        price(general, "1500.00", ist(23, 0), null);
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, bed("G-3", general));
        backdate(id, ist(21, 10));
        preview(clerk, id, ist(24, 9)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.detail", containsString("G-3")));
    }

    @Test
    void asOfBeforeAdmissionIs422() throws Exception {
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, ipd.bed(ward, "V-1"));
        backdate(id, ist(21, 10));
        preview(clerk, id, ist(20, 10)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void withoutInvoiceReadIs403AndOtherOrgIs404() throws Exception {
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, ipd.bed(ward, "X-1"));
        fx.role(org, "ADMISSION_ONLY", "admission:read");
        preview(fx.loggedIn(org, "noinvoice", "ADMISSION_ONLY"), id, ist(24, 9)).andExpect(status().isForbidden());

        UUID other = fx.organization();
        preview(fx.withAllPermissions(other), id, ist(24, 9)).andExpect(status().isNotFound());
    }
}
