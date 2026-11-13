package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [E3-1] Patient registration, UHID allocation and audited detail. Synthetic data only: names are "Test ..."
 * placeholders, phone numbers are +91 followed by a number starting with 0 (no Indian number can start with 0, so
 * none is real), pincodes start with 0 (also not a real postal zone), and no ABHA numbers are used at all.
 * Negative tests use a valid CSRF header and a real session, so a 401/403 can only come from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PatientRegistrationIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String PHONE = "+910000000001";
    private static final String CONTACT_PHONE = "+910000000002";

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
    DocumentNumberAllocator allocator;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session reception;

    @BeforeEach
    void organizationWithAReceptionist() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        reception = fx.loggedIn(org, "reception", "RECEPTIONIST");
    }

    private static String body(String name) {
        return "{\"fullName\":\"" + name + "\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"" + PHONE
                + "\"}";
    }

    private static String fullBody(String name) {
        return "{\"fullName\":\"" + name + "\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"" + PHONE
                + "\",\"address\":{\"line1\":\"1 Demo Street\",\"line2\":\"Test Block\",\"city\":\"Testville\","
                + "\"state\":\"Testland\",\"pincode\":\"000001\"},\"emergencyContact\":{\"name\":\"Test Contact\","
                + "\"relationship\":\"Spouse\",\"phone\":\"" + CONTACT_PHONE + "\"}}";
    }

    private String register(AdminApiFixture.Session session, String json) throws Exception {
        return mockMvc.perform(session.apply(post(URL)).contentType(JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private static String uhid(String response) {
        return JsonPath.read(response, "$.uhid");
    }

    private static UUID id(String response) {
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private int patientRows(UUID organization) {
        return fx.inOrg(organization, () -> jdbc.sql("SELECT count(*) FROM hms.patient WHERE organization_id = ?")
                .param(organization).query(Integer.class).single());
    }

    private Long nextValue(UUID organization) {
        return fx.inOrg(organization, () -> jdbc.sql("""
                SELECT next_value FROM hms.document_sequence
                WHERE organization_id = ? AND document_type = 'UHID' AND period_key = 'ALL'
                """).param(organization).query(Long.class).optional().orElse(null));
    }

    // --- authentication and authorization ------------------------------------------------------------------

    @Test
    void endpointsNeedASession() throws Exception {
        UUID patient = id(register(reception, body("Test Patient One")));
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(URL + "/" + patient)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(JSON).content(body("Test Patient Two")).cookie(csrf)
                .header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isUnauthorized());
        assertThat(patientRows(org)).isEqualTo(1);
        assertThat(nextValue(org)).isEqualTo(2L);
    }

    @Test
    void createNeedsPatientCreateAndDetailNeedsPatientRead() throws Exception {
        fx.role(org, "CREATE_ONLY", "patient:create");
        fx.role(org, "READ_ONLY", "patient:read");
        fx.role(org, "SEARCH_ONLY", "patient:search", "patient:update");
        AdminApiFixture.Session creator = fx.loggedIn(org, "creator", "CREATE_ONLY");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READ_ONLY");
        AdminApiFixture.Session other = fx.loggedIn(org, "other", "SEARCH_ONLY");
        AdminApiFixture.Session doctor = fx.loggedIn(org, "doctor", "DOCTOR");
        AdminApiFixture.Session admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        UUID patient = id(register(reception, body("Test Patient One")));

        mockMvc.perform(creator.apply(post(URL)).contentType(JSON).content(body("Test Patient Two")))
                .andExpect(status().isCreated());
        mockMvc.perform(creator.apply(get(URL + "/" + patient))).andExpect(status().isForbidden());

        mockMvc.perform(reader.apply(get(URL + "/" + patient))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(URL)).contentType(JSON).content(body("Test Patient Three")))
                .andExpect(status().isForbidden());

        // Default templates (PRD personas): Doctor reads only; Facility Administrator holds no patient permission.
        mockMvc.perform(doctor.apply(get(URL + "/" + patient))).andExpect(status().isOk());
        mockMvc.perform(doctor.apply(post(URL)).contentType(JSON).content(body("Test Patient Four")))
                .andExpect(status().isForbidden());
        for (AdminApiFixture.Session without : List.of(other, admin)) {
            mockMvc.perform(without.apply(get(URL + "/" + patient))).andExpect(status().isForbidden());
            mockMvc.perform(without.apply(post(URL)).contentType(JSON).content(body("Test Patient Five")))
                    .andExpect(status().isForbidden());
        }
        assertThat(patientRows(org)).isEqualTo(2);
        assertThat(nextValue(org)).isEqualTo(3L);
    }

    // --- registration and detail ---------------------------------------------------------------------------

    @Test
    void registrationReturnsTheDocumentedShapeAndDetailReturnsTheSameData() throws Exception {
        String created = mockMvc.perform(reception.apply(post(URL)).contentType(JSON)
                        .content(fullBody("  Test Patient One  ")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uhid", is("HMS-000001")))
                .andExpect(jsonPath("$.fullName", is("Test Patient One")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.dateOfBirth", is("1988-04-12")))
                .andExpect(jsonPath("$.dobEstimated", is(false)))
                .andExpect(jsonPath("$.phone", is(PHONE)))
                .andExpect(jsonPath("$.address.line1", is("1 Demo Street")))
                .andExpect(jsonPath("$.address.pincode", is("000001")))
                .andExpect(jsonPath("$.emergencyContact.relationship", is("Spouse")))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(reception.apply(get(URL + "/" + id(created))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(id(created).toString())))
                .andExpect(jsonPath("$.uhid", is("HMS-000001")))
                .andExpect(jsonPath("$.fullName", is("Test Patient One")))
                .andExpect(jsonPath("$.address.city", is("Testville")))
                .andExpect(jsonPath("$.address.line2", is("Test Block")))
                .andExpect(jsonPath("$.emergencyContact.name", is("Test Contact")))
                .andExpect(jsonPath("$.emergencyContact.phone", is(CONTACT_PHONE)));
    }

    @Test
    void addressAndContactAreOptionalAndABareTenDigitNumberBecomesAnIndianE164Number() throws Exception {
        String created = register(reception, "{\"fullName\":\"Test Patient One\",\"sex\":\"M\","
                + "\"dateOfBirth\":\"2001-02-03\",\"phone\":\"0000000003\"}");
        mockMvc.perform(reception.apply(get(URL + "/" + id(created))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone", is("+910000000003")))
                .andExpect(jsonPath("$.address", nullValue()))
                .andExpect(jsonPath("$.emergencyContact", nullValue()));
    }

    @Test
    void anEstimatedAgeIsStoredAsAnEstimatedDateOfBirth() throws Exception {
        String created = register(reception, "{\"fullName\":\"Test Patient One\",\"sex\":\"U\",\"ageYears\":40,"
                + "\"dobEstimated\":true,\"phone\":\"" + PHONE + "\"}");
        LocalDate dob = LocalDate.parse(JsonPath.read(created, "$.dateOfBirth"));
        assertThat(dob.getYear()).isBetween(LocalDate.now().getYear() - 41, LocalDate.now().getYear() - 39);
        assertThat((Boolean) JsonPath.read(created, "$.dobEstimated")).isTrue();
    }

    @Test
    void invalidInputIsRejectedAndConsumesNoUhid() throws Exception {
        String ok = "\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"" + PHONE + "\"";
        String next = "\"fullName\":\"Test Patient One\"," + ok;
        String future = LocalDate.now().plusDays(1).toString();
        List<String> invalid = List.of(
                "{\"fullName\":\"X\"," + ok + "}",
                "{\"fullName\":\"" + "x".repeat(121) + "\"," + ok + "}",
                "{\"fullName\":\"   \"," + ok + "}",
                "{" + ok + "}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"X\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"" + future + "\",\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"1800-01-01\",\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"ageYears\":3,\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"ageYears\":40,\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"ageYears\":131,\"dobEstimated\":true,\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"ageYears\":-1,\"dobEstimated\":true,\"phone\":\"" + PHONE + "\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"12345\"}",
                "{\"fullName\":\"Test Patient One\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"+0123456789\"}",
                "{" + next + ",\"address\":{\"line1\":\"1 Demo Street\",\"city\":\"Testville\",\"state\":\"Testland\",\"pincode\":\"12345\"}}",
                "{" + next + ",\"address\":{\"city\":\"Testville\",\"state\":\"Testland\",\"pincode\":\"000001\"}}",
                "{" + next + ",\"emergencyContact\":{\"name\":\"Test Contact\",\"phone\":\"" + CONTACT_PHONE + "\"}}",
                "{" + next + ",\"emergencyContact\":{\"name\":\"T\",\"relationship\":\"Spouse\",\"phone\":\"" + CONTACT_PHONE + "\"}}");
        for (String json : invalid) {
            mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(json))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        assertThat(patientRows(org)).isZero();
        assertThat(nextValue(org)).as("no failed registration may consume a UHID").isNull();
        assertThat(uhid(register(reception, body("Test Patient One")))).isEqualTo("HMS-000001");
    }

    // --- UHID: unique, gapless, per organization -----------------------------------------------------------

    @Test
    void uhidsAreSequentialPerOrganizationAndIndependentAcrossOrganizations() throws Exception {
        UUID other = fx.organization();
        AdminApiFixture.Session otherReception = fx.loggedIn(other, "reception", "RECEPTIONIST");

        assertThat(uhid(register(reception, body("Test Patient One")))).isEqualTo("HMS-000001");
        assertThat(uhid(register(otherReception, body("Test Patient One")))).isEqualTo("HMS-000001");
        assertThat(uhid(register(reception, body("Test Patient Two")))).isEqualTo("HMS-000002");
        assertThat(uhid(register(reception, body("Test Patient Three")))).isEqualTo("HMS-000003");
        assertThat(uhid(register(otherReception, body("Test Patient Two")))).isEqualTo("HMS-000002");
        assertThat(nextValue(org)).isEqualTo(4L);
        assertThat(nextValue(other)).isEqualTo(3L);
    }

    @Test
    void parallelRegistrationsGetDistinctGaplessUhids() throws Exception {
        int registrations = 24;
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<String> uhids = new ArrayList<>();
        try {
            CyclicBarrier barrier = new CyclicBarrier(threads);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < registrations; i++) {
                String name = "Test Patient " + i;
                int n = i;
                results.add(pool.submit(() -> {
                    if (n < threads) {
                        barrier.await(); // the first wave starts together
                    }
                    var response = mockMvc.perform(reception.apply(post(URL)).contentType(JSON)
                            .content(fullBody(name))).andReturn().getResponse();
                    assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);
                    return uhid(response.getContentAsString());
                }));
            }
            for (Future<String> f : results) {
                uhids.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Set<String> expected = new TreeSet<>();
        for (int i = 1; i <= registrations; i++) {
            expected.add(String.format("HMS-%06d", i));
        }
        assertThat(uhids).hasSize(registrations).doesNotHaveDuplicates();
        assertThat(new TreeSet<>(uhids)).as("gapless 1..N").isEqualTo(expected);
        assertThat(patientRows(org)).isEqualTo(registrations);
        assertThat(nextValue(org)).isEqualTo(registrations + 1L);
        // A second organization registering meanwhile has its own sequence.
        UUID other = fx.organization();
        assertThat(uhid(register(fx.loggedIn(other, "reception", "RECEPTIONIST"), body("Test Patient One"))))
                .isEqualTo("HMS-000001");
    }

    @Test
    void aRolledBackRegistrationDoesNotLeaveAGap() throws Exception {
        assertThat(uhid(register(reception, body("Test Patient One")))).isEqualTo("HMS-000001");

        // A failure after the number was allocated (here: the transaction is rolled back right after).
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            assertThat(allocator.next(org, "UHID", "ALL")).isEqualTo(2L);
            throw new IllegalStateException("simulated failure after allocation");
        })).hasMessageContaining("simulated failure");
        assertThat(nextValue(org)).isEqualTo(2L);

        // A real failure inside the registration after allocation: PostgreSQL text cannot hold a NUL character,
        // so the patient insert fails after the allocator ran and the whole transaction rolls back.
        mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(body("Test\\u0000Patient")))
                .andExpect(status().is5xxServerError());
        assertThat(nextValue(org)).isEqualTo(2L);
        assertThat(patientRows(org)).isEqualTo(1);

        assertThat(uhid(register(reception, body("Test Patient Two")))).isEqualTo("HMS-000002");
        assertThat(nextValue(org)).isEqualTo(3L);
    }

    @Test
    void theDatabaseRefusesADuplicateUhidInTheSameOrganization() throws Exception {
        register(reception, body("Test Patient One"));
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, 'HMS-000001', 'Test Patient Dup', 'U', DATE '1990-01-01', '+910000000009', ?, now(), now())
                """).params(UUID.randomUUID(), org, UUID.randomUUID()).update()))
                .rootCause().hasMessageContaining("uq_patient_org_uhid");
    }

    // --- cross-organization access and RLS -----------------------------------------------------------------

    @Test
    void anotherOrganizationsPatientIsNotFoundAndNotAudited() throws Exception {
        UUID other = fx.organization();
        UUID foreign = id(register(fx.loggedIn(other, "reception", "RECEPTIONIST"), body("Test Patient One")));

        mockMvc.perform(reception.apply(get(URL + "/" + foreign)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code", is("NOT_FOUND")));
        mockMvc.perform(reception.apply(get(URL + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
        assertThat(fx.auditActions(foreign)).containsExactly("PATIENT_CREATED");
    }

    @Test
    void rlsHidesAnotherOrganizationsPatientRowsEvenWithoutAnOrgFilter() throws Exception {
        register(reception, fullBody("Test Patient One"));
        UUID other = fx.organization();
        UUID foreign = id(register(fx.loggedIn(other, "reception", "RECEPTIONIST"), fullBody("Test Patient Two")));
        UUID foreignWithoutChildren = id(register(fx.loggedIn(other, "reception2", "RECEPTIONIST"),
                body("Test Patient Three")));

        for (String table : List.of("patient", "patient_address", "patient_contact")) {
            List<UUID> seen = fx.inOrg(org, () -> jdbc.sql("SELECT DISTINCT organization_id FROM hms." + table)
                    .query(UUID.class).list());
            assertThat(seen).as(table).containsExactly(org);
            Integer noContext = new TransactionTemplate(transactionManager)
                    .execute(s -> jdbc.sql("SELECT count(*) FROM hms." + table).query(Integer.class).single());
            assertThat(noContext).as(table + " without context").isZero();
        }
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.patient WHERE id = ?").param(foreign)
                .query(Integer.class).single())).isZero();
        assertThat(fx.inOrg(org, () -> jdbc.sql("UPDATE hms.patient SET phone = '+910000000008' WHERE id = ?")
                .param(foreign).update())).isZero();
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, 'HMS-000777', 'Test Smuggled', 'U', DATE '1990-01-01', '+910000000007', ?, now(), now())
                """).params(UUID.randomUUID(), other, UUID.randomUUID()).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        // The same-org composite FK stops a child row of this organization pointing at another one's patient.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.patient_contact (id, organization_id, patient_id, name, relationship, phone,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Contact', 'Friend', '+910000000006', now(), now())
                """).params(UUID.randomUUID(), org, foreignWithoutChildren).update()))
                .rootCause().hasMessageContaining("fk_patient_contact_patient");
    }

    @Test
    void patientsAreNeverDeletedByTheRuntimeRoleOrTheApi() throws Exception {
        UUID patient = id(register(reception, fullBody("Test Patient One")));
        for (String table : List.of("patient", "patient_address", "patient_contact")) {
            assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms." + table).update()))
                    .as(table).rootCause().hasMessageContaining("permission denied");
        }
        mockMvc.perform(reception.apply(delete(URL + "/" + patient))).andExpect(status().isMethodNotAllowed());
        assertThat(patientRows(org)).isEqualTo(1);
    }

    // --- audit and PHI -------------------------------------------------------------------------------------

    @Test
    void registrationAndViewAreAuditedWithFieldNamesOnly() throws Exception {
        UUID patient = id(register(reception, fullBody("Test Patient One")));
        mockMvc.perform(reception.apply(get(URL + "/" + patient))).andExpect(status().isOk());
        mockMvc.perform(reception.apply(get(URL + "/" + patient))).andExpect(status().isOk());

        assertThat(fx.auditActions(patient)).containsExactly("PATIENT_CREATED", "PATIENT_VIEWED", "PATIENT_VIEWED");
        String createdFields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? "
                + "AND action = 'PATIENT_CREATED'").param(patient).query(String.class).single();
        assertThat(createdFields).contains("fullName", "dateOfBirth", "phone", "address.city",
                "emergencyContact.phone");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(patient).query(String.class).list();
        assertThat(rows).hasSize(3);
        for (String row : rows) {
            assertThat(row).contains(reception.userId().toString()).contains(org.toString());
            assertThat(row).doesNotContain("Test Patient", "Test Contact", "Testville", "Testland", "Demo Street",
                    "1988-04-12", "910000000001", "910000000002", "HMS-0");
        }
    }

    @Test
    void patientDataNeverAppearsInLogs(CapturedOutput output) throws Exception {
        UUID patient = id(register(reception, fullBody("Zzyzx Qwertyuiop")));
        mockMvc.perform(reception.apply(get(URL + "/" + patient))).andExpect(status().isOk());
        mockMvc.perform(reception.apply(post(URL)).contentType(JSON)
                .content(body("Zzyzx Qwertyuiop").replace("\"F\"", "\"X\""))).andExpect(status().isUnprocessableContent());
        mockMvc.perform(reception.apply(post(URL)).contentType(JSON).content(body("Zzyzx\\u0000Qwertyuiop")))
                .andExpect(status().is5xxServerError());

        assertThat(output.getAll()).doesNotContain("Zzyzx", "Qwertyuiop", "Demo Street", "Testville",
                "910000000001", "910000000002", "1988-04-12");
    }
}
