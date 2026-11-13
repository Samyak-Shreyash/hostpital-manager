package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E3-2] Patient search. Synthetic data only: "Test ..." names, phones +91 followed by a number starting with 0.
 * ABHA search is deferred (no ABHA storage exists yet).
 */
@SpringBootTest
@AutoConfigureMockMvc
class PatientSearchIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";

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
    private AdminApiFixture.Session reception;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        reception = fx.loggedIn(org, "reception", "RECEPTIONIST");
    }

    private void seed(UUID organization, String uhid, String name, String phone, String dob) {
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, 'F', ?::date, ?, ?, now(), now())
                """).params(UUID.randomUUID(), organization, uhid, name, dob, phone, UUID.randomUUID()).update());
    }

    private void seedStandardSet(UUID organization) {
        seed(organization, "HMS-000001", "Test Priya Sharma", "+910000000001", "1988-04-12");
        seed(organization, "HMS-000002", "Test Priya Sharmaa", "+910000000002", "1990-01-01");
        seed(organization, "HMS-000003", "Test Priyanka Sharma", "+910000000003", "1988-04-12");
        seed(organization, "HMS-000004", "Test Rahul Verma", "+910000000004", "1975-07-07");
    }

    private List<String> uhids(String query, Object... vars) throws Exception {
        String json = mockMvc.perform(reception.apply(get(URL + query, vars))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new ArrayList<String>(JsonPath.<List<String>>read(json, "$.items[*].uhid"));
    }

    @Test
    void fuzzyNameMatchTolerantOfTyposAndOrderedBestFirst() throws Exception {
        seedStandardSet(org);
        List<String> typo = uhids("?q=Priya Sharmma");
        assertThat(typo).contains("HMS-000001", "HMS-000002").doesNotContain("HMS-000004");
        assertThat(typo.get(0)).isIn("HMS-000001", "HMS-000002");
        assertThat(uhids("?q=sharma")).contains("HMS-000001", "HMS-000003").doesNotContain("HMS-000004");
        assertThat(uhids("?q=VERMA")).containsExactly("HMS-000004");
        assertThat(uhids("?q=zzzz qqqq")).isEmpty();
    }

    @Test
    void exactUhidPhoneAndDobAndCombinations() throws Exception {
        seedStandardSet(org);
        assertThat(uhids("?uhid=hms-000004")).containsExactly("HMS-000004");
        assertThat(uhids("?phone={p}", "+910000000003")).containsExactly("HMS-000003");
        assertThat(uhids("?phone=0000000002")).containsExactly("HMS-000002");
        assertThat(uhids("?phone={p}", "00000 00002")).containsExactly("HMS-000002");
        assertThat(uhids("?dob=1988-04-12")).containsExactlyInAnyOrder("HMS-000001", "HMS-000003");
        assertThat(uhids("?dob=1988-04-12&q=Priyanka")).containsExactly("HMS-000003");
        assertThat(uhids("?uhid=HMS-000099")).isEmpty();
    }

    @Test
    void responseShapePaginationAndValidation() throws Exception {
        seedStandardSet(org);
        mockMvc.perform(reception.apply(get(URL + "?q=Test&size=2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.totalItems", is(4)))
                .andExpect(jsonPath("$.items[0].fullName").exists())
                .andExpect(jsonPath("$.items[0].phone").exists());
        mockMvc.perform(reception.apply(get(URL + "?q=Test&size=2&page=1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(reception.apply(get(URL))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(reception.apply(get(URL + "?q=a"))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(reception.apply(get(URL + "?phone=12"))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(reception.apply(get(URL + "?q=Test&size=101"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void needsASessionAndPatientSearch() throws Exception {
        seedStandardSet(org);
        mockMvc.perform(get(URL + "?q=Test")).andExpect(status().isUnauthorized());
        fx.role(org, "READ_ONLY", "patient:read");
        fx.role(org, "SEARCH_ONLY", "patient:search");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READ_ONLY");
        AdminApiFixture.Session searcher = fx.loggedIn(org, "searcher", "SEARCH_ONLY");
        AdminApiFixture.Session admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        mockMvc.perform(reader.apply(get(URL + "?q=Test"))).andExpect(status().isForbidden());
        mockMvc.perform(admin.apply(get(URL + "?q=Test"))).andExpect(status().isForbidden());
        assertThat(searchAuditCount(searcher.userId())).isZero();
        mockMvc.perform(searcher.apply(get(URL + "?q=Test"))).andExpect(status().isOk());
        assertThat(searchAuditCount(searcher.userId())).isEqualTo(1);
    }

    @Test
    void anotherOrganizationsPatientsAreNeverReturned() throws Exception {
        seedStandardSet(org);
        UUID other = fx.organization();
        seed(other, "HMS-000001", "Test Priya Sharma", "+910000000001", "1988-04-12");
        seed(other, "HMS-000009", "Test Otherorg Person", "+910000000009", "1999-09-09");
        assertThat(uhids("?q=Otherorg")).isEmpty();
        assertThat(uhids("?uhid=HMS-000009")).isEmpty();
        assertThat(uhids("?phone=0000000009")).isEmpty();
        assertThat(uhids("?dob=1999-09-09")).isEmpty();
        mockMvc.perform(reception.apply(get(URL + "?uhid=HMS-000001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems", is(1)));
        mockMvc.perform(reception.apply(get(URL + "?q=Priya")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems", is(3)));
    }

    @Test
    void oneAuditEventWithResultCountAndNoQueryTerms() throws Exception {
        seedStandardSet(org);
        uhids("?q=Verma");
        uhids("?q=Priya");
        List<Integer> counts = jdbc.sql("""
                SELECT result_count FROM hms.audit_event WHERE action = 'PATIENT_SEARCH' AND actor_id = ?
                ORDER BY occurred_at, id
                """).param(reception.userId()).query(Integer.class).list();
        assertThat(counts).containsExactly(1, 3);
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a "
                + "WHERE action = 'PATIENT_SEARCH' AND actor_id = ?").param(reception.userId())
                .query(String.class).list();
        assertThat(rows).noneMatch(r -> r.contains("Verma") || r.contains("Priya"));
    }

    @Test
    void searchP95Under500msOn100kPatients() throws Exception {
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'HMS-' || lpad(i::text, 6, '0'),
                       'Test ' || substr(md5(i::text), 1, 6) || ' ' || substr(md5((i * 7)::text), 1, 7),
                       'F', date '1950-01-01' + (i % 25000), '+910' || lpad(i::text, 9, '0'), ?, now(), now()
                FROM generate_series(1, 100000) AS i
                """).params(org, UUID.randomUUID()).update());
        jdbc.sql("ANALYZE hms.patient").update();
        seed(org, "HMS-900001", "Test Priya Sharma", "+910000900001", "1988-04-12");
        List<String> queries = List.of("?q=Priya Sharma", "?q=Priya Sharmma", "?q=sharma", "?uhid=HMS-050000",
                "?phone=0000050000", "?dob=1988-04-12&q=Priya", "?q=a3f9c1");
        List<Long> millis = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            for (String q : queries) {
                long t = System.nanoTime();
                mockMvc.perform(reception.apply(get(URL + q))).andExpect(status().isOk());
                millis.add((System.nanoTime() - t) / 1_000_000);
            }
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        assertThat(p95).as("p95 ms over %s", millis).isLessThan(500);
    }

    private int searchAuditCount(UUID searcherId) {
        return jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE action = 'PATIENT_SEARCH' AND actor_id = ?").param(searcherId)
                .query(Integer.class).single();
    }
}
