package com.hms.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.billing.api.ServicePrice;
import com.hms.billing.api.ServicePriceLookup;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.domain.Money;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E2-3] Service catalog with versioned prices. Synthetic data only. Negative tests use a valid CSRF header and a
 * real session, so a 401/403 can only come from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ServiceCatalogIT extends PostgresIT {

    private static final String URL = "/api/v1/services";

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
    ServicePriceLookup lookup;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session admin;

    @BeforeEach
    void organizationWithAFacilityAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
    }

    private String createService(AdminApiFixture.Session session, String code, String name) throws Exception {
        return mockMvc.perform(session.apply(post(URL)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID newService(String code) throws Exception {
        return UUID.fromString(JsonPath.read(createService(admin, code, "Service " + code), "$.id"));
    }

    private static String priceBody(String amount, Instant from) {
        return "{\"amount\":\"" + amount + "\"" + (from == null ? "" : ",\"effectiveFrom\":\"" + from + "\"") + "}";
    }

    private String addPrice(AdminApiFixture.Session session, UUID service, String amount, Instant from)
            throws Exception {
        return mockMvc.perform(session.apply(post(URL + "/" + service + "/prices"))
                        .contentType(MediaType.APPLICATION_JSON).content(priceBody(amount, from)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static Instant inFuture(long days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
    }

    private Optional<ServicePrice> priceAt(UUID service, Instant at) {
        return fx.inOrg(org, () -> lookup.priceAt(org, service, at));
    }

    private int priceRows(UUID service) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.service_price WHERE service_id = ?")
                .param(service).query(Integer.class).single());
    }

    // --- authentication and authorization ------------------------------------------------------------------

    @Test
    void endpointsNeedASession() throws Exception {
        UUID service = newService("CONSULT");
        Cookie csrf = fx.csrf();
        String json = MediaType.APPLICATION_JSON_VALUE;
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/" + service)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/" + service + "/prices")).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(json).content("{\"code\":\"XX\",\"name\":\"X\"}").cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(URL + "/" + service).contentType(json).content("{\"version\":0,\"name\":\"X\"}")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL + "/" + service + "/prices").contentType(json).content(priceBody("1.00", null))
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isUnauthorized());
        assertThat(priceRows(service)).isZero();
    }

    @Test
    void readNeedsCatalogReadAndWritesNeedCatalogManage() throws Exception {
        fx.role(org, "READ_ONLY", "catalog:read");
        fx.role(org, "MANAGE_ONLY", "catalog:manage");
        fx.role(org, "NOTHING_USEFUL", "org:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READ_ONLY");
        AdminApiFixture.Session manager = fx.loggedIn(org, "manager", "MANAGE_ONLY");
        AdminApiFixture.Session outsider = fx.loggedIn(org, "outsider", "NOTHING_USEFUL");
        UUID service = newService("CONSULT");
        String createBody = "{\"code\":\"NEWCODE\",\"name\":\"New Entry\"}";
        String patchBody = "{\"version\":0,\"name\":\"Renamed\"}";
        String json = MediaType.APPLICATION_JSON_VALUE;

        mockMvc.perform(reader.apply(get(URL))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get(URL + "/" + service))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get(URL + "/" + service + "/prices"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(URL)).contentType(json).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch(URL + "/" + service)).contentType(json).content(patchBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(post(URL + "/" + service + "/prices")).contentType(json)
                .content(priceBody("10.00", null))).andExpect(status().isForbidden());

        for (AdminApiFixture.Session noRead : List.of(manager, outsider)) {
            mockMvc.perform(noRead.apply(get(URL))).andExpect(status().isForbidden());
            mockMvc.perform(noRead.apply(get(URL + "/" + service))).andExpect(status().isForbidden());
            mockMvc.perform(noRead.apply(get(URL + "/" + service + "/prices"))).andExpect(status().isForbidden());
        }
        mockMvc.perform(outsider.apply(post(URL)).contentType(json).content(createBody))
                .andExpect(status().isForbidden());
        mockMvc.perform(outsider.apply(post(URL + "/" + service + "/prices")).contentType(json)
                .content(priceBody("10.00", null))).andExpect(status().isForbidden());
        assertThat(priceRows(service)).isZero();

        mockMvc.perform(manager.apply(post(URL)).contentType(json).content(createBody))
                .andExpect(status().isCreated());
        mockMvc.perform(manager.apply(patch(URL + "/" + service)).contentType(json).content(patchBody))
                .andExpect(status().isOk());
        mockMvc.perform(manager.apply(post(URL + "/" + service + "/prices")).contentType(json)
                .content(priceBody("10.00", null))).andExpect(status().isCreated());
    }

    // --- service CRUD ---------------------------------------------------------------------------------------

    @Test
    void createReturnsTheDocumentedShapeAndAuditsFieldNamesOnly() throws Exception {
        mockMvc.perform(admin.apply(post(URL)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CONSULT\",\"name\":\"  Consultation  \"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("CONSULT")))
                .andExpect(jsonPath("$.name", is("Consultation")))
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.currentPrice", nullValue()))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.id").isNotEmpty());

        UUID id = fx.inOrg(org, () -> jdbc.sql(
                "SELECT id FROM hms.service_catalog_item WHERE organization_id = ? AND code = 'CONSULT'")
                .param(org).query(UUID.class).single());
        assertThat(fx.auditActions(id)).containsExactly("SERVICE_CREATED");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ?")
                .param(id).query(String.class).single();
        assertThat(fields).contains("code", "name", "active").doesNotContain("Consultation", "CONSULT");
    }

    @Test
    void codeIsUniquePerOrganizationButReusableInAnotherOne() throws Exception {
        createService(admin, "CONSULT", "Consultation");
        mockMvc.perform(admin.apply(post(URL)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"CONSULT\",\"name\":\"Another\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_SERVICE_CODE")));
        UUID other = fx.organization();
        createService(fx.loggedIn(other, "facility", "FACILITY_ADMIN"), "CONSULT", "Consultation");
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        UUID service = newService("CONSULT");
        for (String body : List.of(
                "{\"code\":\"consult\",\"name\":\"Lower Case\"}",
                "{\"code\":\"C\",\"name\":\"Too Short\"}",
                "{\"code\":\"" + "A".repeat(31) + "\",\"name\":\"Too Long\"}",
                "{\"code\":\"BAD-CODE\",\"name\":\"Bad Character\"}",
                "{\"code\":\"CONSULT2\",\"name\":\"   \"}",
                "{\"code\":\"CONSULT2\"}",
                "{\"name\":\"No Code\"}",
                "{\"code\":\"CONSULT2\",\"name\":\"" + "x".repeat(151) + "\"}")) {
            mockMvc.perform(admin.apply(post(URL)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        for (String body : List.of("{\"version\":0}", "{\"name\":\"No Version\"}", "{\"version\":0,\"name\":\"  \"}")) {
            mockMvc.perform(admin.apply(patch(URL + "/" + service)).contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.service_catalog_item WHERE organization_id = ?")
                .param(org).query(Integer.class).single())).isEqualTo(1);
    }

    @Test
    void listIsScopedFilteredPagedAndShowsTheCurrentPrice() throws Exception {
        UUID alpha = UUID.fromString(JsonPath.read(createService(admin, "ALPHA", "Alpha"), "$.id"));
        String beta = createService(admin, "BETA", "Beta");
        createService(admin, "GAMMA", "Gamma");
        addPrice(admin, alpha, "250.50", null);
        mockMvc.perform(admin.apply(patch(URL + "/" + JsonPath.read(beta, "$.id")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"active\":false}"))
                .andExpect(status().isOk());
        UUID other = fx.organization();
        createService(fx.loggedIn(other, "facility", "FACILITY_ADMIN"), "OTHER", "Other Organization Entry");

        mockMvc.perform(admin.apply(get(URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.totalItems", is(3)))
                .andExpect(jsonPath("$.items[0].code", is("ALPHA")))
                .andExpect(jsonPath("$.items[0].currentPrice", is("250.50")))
                .andExpect(jsonPath("$.items[1].currentPrice", nullValue()))
                .andExpect(jsonPath("$.items[2].code", is("GAMMA")));
        mockMvc.perform(admin.apply(get(URL + "?active=true"))).andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(admin.apply(get(URL + "?active=false")))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.items[0].code", is("BETA")));
        mockMvc.perform(admin.apply(get(URL + "?page=1&size=2")))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.totalItems", is(3)));
        for (String bad : List.of("?size=0", "?size=101", "?page=-1")) {
            mockMvc.perform(admin.apply(get(URL + bad))).andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
    }

    @Test
    void patchRenamesDeactivatesReactivatesAndChecksTheVersion() throws Exception {
        String created = createService(admin, "CONSULT", "Consultation");
        String id = JsonPath.read(created, "$.id");
        String json = MediaType.APPLICATION_JSON_VALUE;

        mockMvc.perform(admin.apply(patch(URL + "/" + id)).contentType(json)
                        .content("{\"version\":0,\"name\":\"Consultation Visit\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.code", is("CONSULT")));
        mockMvc.perform(admin.apply(patch(URL + "/" + id)).contentType(json)
                        .content("{\"version\":1,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active", is(false)));
        mockMvc.perform(admin.apply(patch(URL + "/" + id)).contentType(json)
                        .content("{\"version\":2,\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(3)));
        mockMvc.perform(admin.apply(patch(URL + "/" + id)).contentType(json)
                        .content("{\"version\":0,\"name\":\"Stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        assertThat(fx.auditActions(UUID.fromString(id))).containsExactly("SERVICE_CREATED", "SERVICE_UPDATED",
                "SERVICE_DEACTIVATED", "SERVICE_ACTIVATED");
    }

    @Test
    void anotherOrganizationsServiceIsNotFoundAndUntouched() throws Exception {
        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        UUID foreign = UUID.fromString(JsonPath.read(createService(otherAdmin, "CONSULT", "Foreign"), "$.id"));
        String json = MediaType.APPLICATION_JSON_VALUE;

        mockMvc.perform(admin.apply(get(URL + "/" + foreign))).andExpect(status().isNotFound());
        mockMvc.perform(admin.apply(get(URL + "/" + foreign + "/prices"))).andExpect(status().isNotFound());
        mockMvc.perform(admin.apply(patch(URL + "/" + foreign)).contentType(json)
                .content("{\"version\":0,\"name\":\"Hijacked\"}")).andExpect(status().isNotFound());
        mockMvc.perform(admin.apply(post(URL + "/" + foreign + "/prices")).contentType(json)
                .content(priceBody("1.00", null))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mockMvc.perform(admin.apply(get(URL + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(fx.inOrg(other, () -> jdbc.sql("SELECT count(*) FROM hms.service_price WHERE service_id = ?")
                .param(foreign).query(Integer.class).single())).isZero();
    }

    // --- versioned prices -----------------------------------------------------------------------------------

    @Test
    void aPriceChangeAddsAVersionAndLeavesEarlierOnesAndTheirCopiesUnchanged() throws Exception {
        UUID service = newService("CONSULT");
        String first = addPrice(admin, service, "500", null);
        mockMvc.perform(admin.apply(get(URL + "/" + service))).andExpect(jsonPath("$.currentPrice", is("500.00")));
        UUID firstId = UUID.fromString(JsonPath.read(first, "$.id"));
        Instant firstFrom = Instant.parse(JsonPath.read(first, "$.effectiveFrom"));
        assertThat((Object) JsonPath.read(first, "$.amount")).isEqualTo("500.00");
        assertThat((Object) JsonPath.read(first, "$.effectiveTo")).isNull();

        // What an invoice line does: copy the price (amount and id) at the time of billing.
        Instant billedAt = firstFrom.plusSeconds(1);
        ServicePrice copied = priceAt(service, billedAt).orElseThrow();
        Money copiedAmount = copied.amount();
        assertThat(copiedAmount).isEqualTo(Money.of("500.00"));

        Instant change = inFuture(30);
        String second = addPrice(admin, service, "650.75", change);
        assertThat((Object) JsonPath.read(second, "$.amount")).isEqualTo("650.75");

        // The earlier version is intact except that its open end is now the successor's start.
        mockMvc.perform(admin.apply(get(URL + "/" + service + "/prices")))
                .andExpect(jsonPath("$.items", hasSize(2))).andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.items[0].amount", is("650.75")))
                .andExpect(jsonPath("$.items[0].effectiveTo", nullValue()))
                .andExpect(jsonPath("$.items[1].id", is(firstId.toString())))
                .andExpect(jsonPath("$.items[1].amount", is("500.00")))
                .andExpect(jsonPath("$.items[1].effectiveFrom", is(firstFrom.toString())))
                .andExpect(jsonPath("$.items[1].effectiveTo", is(change.toString())));
        ServicePrice again = priceAt(service, billedAt).orElseThrow();
        assertThat(again.id()).isEqualTo(copied.id());
        assertThat(again.amount()).isEqualTo(copiedAmount);
        // Until the change takes effect the catalog still quotes the old price.
        mockMvc.perform(admin.apply(get(URL + "/" + service))).andExpect(jsonPath("$.currentPrice", is("500.00")));
        assertThat(priceAt(service, change.plusSeconds(1)).orElseThrow().amount()).isEqualTo(Money.of("650.75"));
        assertThat(fx.auditActions(firstId)).containsExactly("SERVICE_PRICE_CREATED");
        UUID secondId = UUID.fromString(JsonPath.read(second, "$.id"));
        assertThat(fx.auditActions(secondId)).containsExactly("SERVICE_PRICE_CREATED");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ?")
                .param(secondId).query(String.class).single();
        assertThat(fields).contains("amount", "effectiveFrom").doesNotContain("650");
    }

    @Test
    void lookupAtTheBoundariesIsHalfOpen() throws Exception {
        UUID service = newService("XRAY");
        Instant t1 = inFuture(10);
        Instant t2 = inFuture(20);
        addPrice(admin, service, "100.00", t1);
        addPrice(admin, service, "200.00", t2);

        assertThat(priceAt(service, t1.minusNanos(1_000))).isEmpty();
        assertThat(priceAt(service, t1).orElseThrow().amount()).isEqualTo(Money.of("100.00"));
        assertThat(priceAt(service, t2.minusNanos(1_000)).orElseThrow().amount()).isEqualTo(Money.of("100.00"));
        ServicePrice atSwitch = priceAt(service, t2).orElseThrow();
        assertThat(atSwitch.amount()).isEqualTo(Money.of("200.00"));
        assertThat(atSwitch.effectiveFrom()).isEqualTo(t2);
        assertThat(atSwitch.effectiveTo()).isNull();
        assertThat(priceAt(service, t2.plus(3650, ChronoUnit.DAYS)).orElseThrow().amount())
                .isEqualTo(Money.of("200.00"));
        ServicePrice old = priceAt(service, t1).orElseThrow();
        assertThat(old.effectiveTo()).isEqualTo(t2);
        // Unknown service, and another organization's service id, have no price.
        assertThat(priceAt(UUID.randomUUID(), t2)).isEmpty();
        UUID other = fx.organization();
        assertThat(fx.inOrg(other, () -> lookup.priceAt(other, service, t2))).isEmpty();
    }

    @Test
    void aNewVersionMustStartAfterTheLatestOneAndNotInThePast() throws Exception {
        UUID service = newService("LAB");
        Instant t1 = inFuture(10);
        addPrice(admin, service, "100.00", t1);
        String json = MediaType.APPLICATION_JSON_VALUE;
        String url = URL + "/" + service + "/prices";

        for (Instant clash : List.of(t1, t1.minusSeconds(60), t1.minus(1, ChronoUnit.DAYS).plusSeconds(1))) {
            if (clash.isBefore(Instant.now())) {
                continue;
            }
            mockMvc.perform(admin.apply(post(url)).contentType(json).content(priceBody("120.00", clash)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("PRICE_RANGE_OVERLAP")));
        }
        mockMvc.perform(admin.apply(post(url)).contentType(json)
                        .content(priceBody("120.00", Instant.now().minus(1, ChronoUnit.DAYS))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("PRICE_EFFECTIVE_IN_PAST")));
        // "now" would start before the future-dated latest version: also an overlap.
        mockMvc.perform(admin.apply(post(url)).contentType(json).content(priceBody("120.00", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("PRICE_RANGE_OVERLAP")));
        assertThat(priceRows(service)).isEqualTo(1);
    }

    @Test
    void invalidPriceInputIsRejected() throws Exception {
        UUID service = newService("LAB");
        String url = URL + "/" + service + "/prices";
        String json = MediaType.APPLICATION_JSON_VALUE;
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":\"-1.00\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // Money is a decimal string with at most two decimals; JSON numbers and extra precision are refused.
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":500.00}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":\"10.005\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":\"abc\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":\"1.00\",\"effectiveFrom\":\"soon\"}"))
                .andExpect(status().isBadRequest());
        assertThat(priceRows(service)).isZero();
        // A zero price (free service) is allowed.
        mockMvc.perform(admin.apply(post(url)).contentType(json).content("{\"amount\":\"0\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.amount", is("0.00")));
    }

    // --- database guarantees --------------------------------------------------------------------------------

    private void insertPrice(UUID service, String amount, Instant from, Instant to) {
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, effective_to,
                    created_by, created_at) VALUES (?, ?, ?, ?::numeric, ?, ?, ?, now())""")
                .params(UUID.randomUUID(), org, service, amount, Timestamp.from(from),
                        to == null ? null : Timestamp.from(to), UUID.randomUUID()).update());
    }

    @Test
    void theDatabaseRejectsOverlappingRangesButAllowsAdjacentOnes() throws Exception {
        UUID service = newService("OVERLAP");
        UUID otherService = newService("OTHERSVC");
        Instant a = inFuture(10);
        Instant b = inFuture(20);
        insertPrice(service, "10.00", a, b);

        for (Instant[] range : new Instant[][] {{a, b}, {a.plusSeconds(1), b.minusSeconds(1)},
                {a.minusSeconds(60), a.plusSeconds(60)}, {b.minusSeconds(1), null}, {a.minus(1, ChronoUnit.DAYS), null}}) {
            assertThatThrownBy(() -> insertPrice(service, "11.00", range[0], range[1]))
                    .rootCause().hasMessageContaining("ex_service_price_no_overlap");
        }
        // [a,b) and [b,...) share only the instant b, which belongs to the second: allowed.
        insertPrice(service, "12.00", b, null);
        // Another service has its own timeline.
        insertPrice(otherService, "13.00", a, null);
        assertThatThrownBy(() -> insertPrice(service, "14.00", b.plusSeconds(5), null))
                .rootCause().hasMessageContaining("ex_service_price_no_overlap");
        assertThatThrownBy(() -> insertPrice(service, "-1.00", inFuture(90), null))
                .rootCause().hasMessageContaining("ck_service_price_amount");
        assertThatThrownBy(() -> insertPrice(service, "1.00", inFuture(90), inFuture(80)))
                .rootCause().hasMessageContaining("ck_service_price_range");
    }

    @Test
    void concurrentInsertsOfOverlappingRangesLeaveExactlyOne() throws Exception {
        UUID service = newService("RACE");
        Instant from = inFuture(5);
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String amount = (100 + i) + ".00";
                Callable<Boolean> task = () -> {
                    barrier.await();
                    try {
                        insertPrice(service, amount, from, null);
                        return true;
                    } catch (RuntimeException e) {
                        Throwable root = e;
                        while (root.getCause() != null) {
                            root = root.getCause();
                        }
                        assertThat(root.getMessage()).contains("ex_service_price_no_overlap");
                        return false;
                    }
                };
                results.add(pool.submit(task));
            }
            int winners = 0;
            for (Future<Boolean> f : results) {
                winners += f.get() ? 1 : 0;
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(priceRows(service)).isEqualTo(1);
    }

    @Test
    void concurrentPriceChangesThroughTheApiAnswerOneCreatedAndTheRestConflict() throws Exception {
        UUID service = newService("APIRACE");
        Instant from = inFuture(7);
        int threads = 5;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String amount = (200 + i) + ".00";
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(admin.apply(post(URL + "/" + service + "/prices"))
                            .contentType(MediaType.APPLICATION_JSON).content(priceBody(amount, from)))
                            .andReturn().getResponse();
                    if (response.getStatus() == 409) {
                        assertThat((Object) JsonPath.read(response.getContentAsString(), "$.code"))
                                .isEqualTo("PRICE_RANGE_OVERLAP");
                    }
                    return response.getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(priceRows(service)).isEqualTo(1);
    }

    @Test
    void pricesAreNeverUpdatedInPlaceOrDeletedByTheRuntimeRole() throws Exception {
        UUID service = newService("IMMUTABLE");
        Instant t2 = inFuture(10);
        addPrice(admin, service, "100.00", null);
        addPrice(admin, service, "200.00", t2);

        // hms_app may only touch effective_to: any other column is refused by privileges.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("UPDATE hms.service_price SET amount = 1 WHERE service_id = ?")
                .param(service).update())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.service_price SET effective_from = effective_from - interval '1 day' WHERE service_id = ?")
                .param(service).update())).rootCause().hasMessageContaining("permission denied");
        // ... and effective_to itself can be set once, never moved or cleared (trigger), even by a buggy query.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.service_price SET effective_to = effective_to + interval '1 day' "
                        + "WHERE service_id = ? AND effective_to IS NOT NULL").param(service).update()))
                .rootCause().hasMessageContaining("immutable row");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "UPDATE hms.service_price SET effective_to = NULL WHERE service_id = ? AND effective_to IS NOT NULL")
                .param(service).update())).rootCause().hasMessageContaining("immutable row");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.service_price WHERE service_id = ?")
                .param(service).update())).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.service_catalog_item WHERE id = ?")
                .param(service).update())).rootCause().hasMessageContaining("permission denied");
        mockMvc.perform(admin.apply(delete(URL + "/" + service))).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(admin.apply(delete(URL + "/" + service + "/prices"))).andExpect(status().isMethodNotAllowed());
        assertThat(priceRows(service)).isEqualTo(2);
        assertThat(fx.inOrg(org, () -> jdbc.sql(
                "SELECT amount::text FROM hms.service_price WHERE service_id = ? ORDER BY effective_from")
                .param(service).query(String.class).list())).containsExactly("100.00", "200.00");
    }

    @Test
    void rlsHidesAnotherOrganizationsRowsEvenWithoutAnOrgFilter() throws Exception {
        UUID service = newService("CONSULT");
        addPrice(admin, service, "100.00", null);
        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        UUID foreign = UUID.fromString(JsonPath.read(createService(otherAdmin, "CONSULT", "Foreign"), "$.id"));
        addPrice(otherAdmin, foreign, "999.00", null);

        for (String table : List.of("service_catalog_item", "service_price")) {
            List<UUID> seen = fx.inOrg(org, () -> jdbc.sql("SELECT DISTINCT organization_id FROM hms." + table)
                    .query(UUID.class).list());
            assertThat(seen).as(table).containsExactly(org);
            Integer noContext = new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .execute(s -> jdbc.sql("SELECT count(*) FROM hms." + table).query(Integer.class).single());
            assertThat(noContext).as(table + " without context").isZero();
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("UPDATE hms.service_catalog_item SET name = 'x' WHERE id = ?")
                .param(foreign).update())).isZero();
        assertThatThrownBy(() -> insertPriceFor(other, foreign))
                .rootCause().hasMessageContaining("row-level security policy");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.service_catalog_item (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, 'SMUGGLED', 'Smuggled', now(), now())""").params(UUID.randomUUID(), other).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        // A same-org composite FK also stops a price pointing at another organization's service.
        UUID foreignWithoutPrice = UUID.fromString(JsonPath.read(createService(otherAdmin, "NOPRICE", "Foreign"), "$.id"));
        assertThatThrownBy(() -> insertPriceFor(org, foreignWithoutPrice))
                .rootCause().hasMessageContaining("fk_service_price_service");
    }

    private void insertPriceFor(UUID priceOrg, UUID service) {
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.service_price (id, organization_id, service_id, amount, effective_from, created_by,
                    created_at) VALUES (?, ?, ?, 1.00, now() + interval '400 days', ?, now())""")
                .params(UUID.randomUUID(), priceOrg, service, UUID.randomUUID()).update());
    }
}
