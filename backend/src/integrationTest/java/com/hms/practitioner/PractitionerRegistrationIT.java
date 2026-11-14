package com.hms.practitioner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E4-1] Doctor registration: user link, specialties (exactly one primary), departments, registration number.
 * Synthetic data only: "Dr. Test ..." names and "TEST-..." registration numbers. Negative tests use a real session
 * and CSRF token, so a 401/403 can only come from the rule under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PractitionerRegistrationIT extends PostgresIT {

    private static final String URL = "/api/v1/doctors";
    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;

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
    private AdminApiFixture.Session admin;
    private UUID cardiology;
    private UUID medicine;
    private UUID opd;

    @BeforeEach
    void organizationWithAFacilityAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        cardiology = specialty(org, "CARDIO", true);
        medicine = specialty(org, "GENMED", true);
        opd = department(org, "OPD", true);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private UUID specialty(UUID organization, String code, boolean active) {
        return catalog("specialty", organization, code, active);
    }

    private UUID department(UUID organization, String code, boolean active) {
        return catalog("department", organization, code, active);
    }

    private UUID catalog(String table, UUID organization, String code, boolean active) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("INSERT INTO hms." + table
                        + " (id, organization_id, code, name, active, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, now(), now())")
                .params(id, organization, code, "Test " + code, active).update());
        return id;
    }

    private UUID doctorUser(UUID organization) {
        return fx.user(organization, fx.uniqueEmail("doctor"), "DOCTOR");
    }

    private static String body(UUID userId, String registrationNo, List<UUID> specialties, UUID primary,
            List<UUID> departments) {
        return "{\"userId\":\"" + userId + "\",\"fullName\":\"Dr. Test Doctor\",\"registrationNo\":\""
                + registrationNo + "\",\"registrationCouncil\":\"Test Council\",\"qualifications\":[\"MBBS\",\"MD\"],"
                + "\"specialtyIds\":" + ids(specialties) + (primary == null ? ""
                : ",\"primarySpecialtyId\":\"" + primary + "\"") + ",\"departmentIds\":" + ids(departments) + "}";
    }

    private static String ids(List<UUID> values) {
        return values.stream().map(v -> "\"" + v + "\"").reduce((a, b) -> a + "," + b)
                .map(s -> "[" + s + "]").orElse("[]");
    }

    private String register(String json) throws Exception {
        return mockMvc.perform(admin.apply(post(URL)).contentType(JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    private UUID registerDoctor(String registrationNo) throws Exception {
        return id(register(body(doctorUser(org), registrationNo, List.of(cardiology, medicine), cardiology,
                List.of(opd))));
    }

    private static UUID id(String response) {
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private int practitionerRows(UUID organization) {
        return fx.inOrg(organization, () -> jdbc.sql("SELECT count(*) FROM hms.practitioner")
                .query(Integer.class).single());
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String content) {
        return admin.apply(request).contentType(JSON).content(content);
    }

    // --- authentication and authorization ---------------------------------------------------------------------

    @Test
    void endpointsNeedASession() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(URL + "/" + doctor)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(JSON).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .content(body(doctorUser(org), "TEST-1002", List.of(cardiology), cardiology, List.of())))
                .andExpect(status().isUnauthorized());
        assertThat(practitionerRows(org)).isEqualTo(1);
    }

    @Test
    void eachEndpointNeedsItsPermission() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        fx.role(org, "PRC_READ", "practitioner:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "PRC_READ");
        AdminApiFixture.Session reception = fx.loggedIn(org, "reception", "RECEPTIONIST");

        mockMvc.perform(reader.apply(get(URL + "/" + doctor))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get(URL))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(URL)).contentType(JSON)
                        .content(body(doctorUser(org), "TEST-1002", List.of(cardiology), cardiology, List.of())))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch(URL + "/" + doctor)).contentType(JSON)
                .content("{\"version\":0,\"fullName\":\"Dr. Test Renamed\"}")).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(put(URL + "/" + doctor + "/specialties")).contentType(JSON)
                        .content("{\"version\":0,\"specialtyIds\":[\"" + medicine + "\"],\"primarySpecialtyId\":\""
                                + medicine + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(put(URL + "/" + doctor + "/departments")).contentType(JSON)
                .content("{\"version\":0,\"departmentIds\":[]}")).andExpect(status().isForbidden());
        mockMvc.perform(reception.apply(get(URL + "/" + doctor))).andExpect(status().isForbidden());
        assertThat(practitionerRows(org)).isEqualTo(1);
    }

    // --- registration -----------------------------------------------------------------------------------------

    @Test
    void registrationReturnsTheDocumentedShapeAndIsAuditedWithFieldNamesOnly() throws Exception {
        UUID user = doctorUser(org);
        String response = register(body(user, "test-1001", List.of(cardiology, medicine), medicine, List.of(opd)));
        UUID doctor = id(response);
        mockMvc.perform(admin.apply(get(URL + "/" + doctor))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(user.toString())))
                .andExpect(jsonPath("$.fullName", is("Dr. Test Doctor")))
                .andExpect(jsonPath("$.registrationNo", is("TEST-1001")))
                .andExpect(jsonPath("$.registrationCouncil", is("Test Council")))
                .andExpect(jsonPath("$.qualifications", containsInAnyOrder("MBBS", "MD")))
                .andExpect(jsonPath("$.defaultSlotMinutes", is(15)))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.primarySpecialtyId", is(medicine.toString())))
                .andExpect(jsonPath("$.specialtyIds", containsInAnyOrder(cardiology.toString(), medicine.toString())))
                .andExpect(jsonPath("$.departmentIds", containsInAnyOrder(opd.toString())))
                .andExpect(jsonPath("$.version", is(0)));
        assertThat(fx.auditActions(doctor)).containsExactly("PRACTITIONER_CREATED");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ?")
                .param(doctor).query(String.class).single();
        assertThat(fields).contains("registrationNo", "primarySpecialtyId").doesNotContain("TEST-1001", "Dr.");
    }

    @Test
    void manyDoctorsPerSpecialtyAndTheListFiltersBySpecialtyAndDepartment() throws Exception {
        UUID first = registerDoctor("TEST-1001");
        UUID second = registerDoctor("TEST-1002");
        UUID third = id(register(body(doctorUser(org), "TEST-1003", List.of(medicine), medicine, List.of())));
        mockMvc.perform(admin.apply(get(URL).param("specialtyId", cardiology.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems", is(2)))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(first.toString(), second.toString())));
        mockMvc.perform(admin.apply(get(URL).param("specialtyId", medicine.toString())))
                .andExpect(jsonPath("$.totalItems", is(3)));
        mockMvc.perform(admin.apply(get(URL).param("departmentId", opd.toString())))
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(admin.apply(get(URL).param("q", "test-1003")))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(third.toString())));
    }

    @Test
    void registrationNumberIsUniquePerOrganizationIgnoringCase() throws Exception {
        registerDoctor("TEST-1001");
        mockMvc.perform(json(post(URL), body(doctorUser(org), "test-1001", List.of(cardiology), cardiology,
                        List.of())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DUPLICATE_REGISTRATION_NO")));
        // Another organization may use the same number.
        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        UUID otherSpecialty = specialty(other, "CARDIO", true);
        mockMvc.perform(otherAdmin.apply(post(URL)).contentType(JSON).content(body(doctorUser(other), "TEST-1001",
                List.of(otherSpecialty), otherSpecialty, List.of()))).andExpect(status().isCreated());
        // Changing another doctor's number to a taken one is refused too.
        UUID second = registerDoctor("TEST-1002");
        mockMvc.perform(json(patch(URL + "/" + second), "{\"version\":0,\"registrationNo\":\"TEST-1001\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("DUPLICATE_REGISTRATION_NO")));
        assertThat(practitionerRows(org)).isEqualTo(2);
    }

    @Test
    void aUserCanBeLinkedToOnlyOneDoctor() throws Exception {
        UUID user = doctorUser(org);
        register(body(user, "TEST-1001", List.of(cardiology), cardiology, List.of()));
        mockMvc.perform(json(post(URL), body(user, "TEST-1002", List.of(cardiology), cardiology, List.of())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_ALREADY_LINKED")));
        assertThat(practitionerRows(org)).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesASecondPractitionerForTheSameUser() throws Exception {
        UUID user = doctorUser(org);
        register(body(user, "TEST-1001", List.of(cardiology), cardiology, List.of()));
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            UUID id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no, created_by,
                        created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Copy', 'TEST-1002', ?, now(), now())""")
                    .params(id, org, user, user).update();
            return jdbc.sql("""
                    INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id, specialty_id, is_primary,
                        created_at) VALUES (?, ?, ?, true, now())""").params(org, id, cardiology).update();
        })).hasMessageContaining("uq_practitioner_user");
    }

    @Test
    void parallelRegistrationsOfOneUserCreateOneDoctor() throws Exception {
        UUID user = doctorUser(org);
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String json = body(user, "TEST-20" + i, List.of(cardiology), cardiology, List.of());
                results.add(pool.submit(() -> {
                    barrier.await();
                    return mockMvc.perform(json(post(URL), json)).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new java.util.ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(practitionerRows(org)).isEqualTo(1);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        UUID user = doctorUser(org);
        // No primary specialty.
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology), null, List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // Primary not among the specialties.
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology), medicine, List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        // No specialties at all.
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(), cardiology, List.of())))
                .andExpect(status().isUnprocessableEntity());
        // Duplicate specialty id, bad registration number, slot out of range.
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology, cardiology), cardiology,
                List.of()))).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(URL), body(user, "TEST 1001!", List.of(cardiology), cardiology, List.of())))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology), cardiology, List.of())
                        .replace("\"departmentIds\"", "\"defaultSlotMinutes\":3,\"departmentIds\"")))
                .andExpect(status().isUnprocessableEntity());
        assertThat(practitionerRows(org)).isZero();
    }

    @Test
    void inactiveOrUnknownReferencesAreRejected() throws Exception {
        UUID retiredSpecialty = specialty(org, "RETIRED", false);
        UUID retiredDepartment = department(org, "OLDWARD", false);
        UUID user = doctorUser(org);
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(retiredSpecialty), retiredSpecialty,
                        List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("SPECIALTY_NOT_AVAILABLE")));
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology), cardiology,
                        List.of(retiredDepartment))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("DEPARTMENT_NOT_AVAILABLE")));
        mockMvc.perform(json(post(URL), body(UUID.randomUUID(), "TEST-1001", List.of(cardiology), cardiology,
                        List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("USER_NOT_AVAILABLE")));
        UUID inactiveUser = doctorUser(org);
        jdbc.sql("UPDATE hms.app_user SET status = 'INACTIVE' WHERE id = ?").param(inactiveUser).update();
        mockMvc.perform(json(post(URL), body(inactiveUser, "TEST-1001", List.of(cardiology), cardiology,
                        List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("USER_NOT_AVAILABLE")));
        assertThat(practitionerRows(org)).isZero();
    }

    @Test
    void anotherOrganizationsUserSpecialtyOrDepartmentIsRejectedLikeAnUnknownId() throws Exception {
        UUID other = fx.organization();
        UUID foreignUser = doctorUser(other);
        UUID foreignSpecialty = specialty(other, "CARDIO", true);
        UUID foreignDepartment = department(other, "OPD", true);
        UUID user = doctorUser(org);
        mockMvc.perform(json(post(URL), body(foreignUser, "TEST-1001", List.of(cardiology), cardiology, List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("USER_NOT_AVAILABLE")));
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(foreignSpecialty), foreignSpecialty,
                        List.of())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("SPECIALTY_NOT_AVAILABLE")));
        mockMvc.perform(json(post(URL), body(user, "TEST-1001", List.of(cardiology), cardiology,
                        List.of(foreignDepartment))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("DEPARTMENT_NOT_AVAILABLE")));
        UUID doctor = registerDoctor("TEST-1002");
        mockMvc.perform(json(put(URL + "/" + doctor + "/specialties"), "{\"version\":0,\"specialtyIds\":[\""
                        + foreignSpecialty + "\"],\"primarySpecialtyId\":\"" + foreignSpecialty + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(json(put(URL + "/" + doctor + "/departments"), "{\"version\":0,\"departmentIds\":[\""
                + foreignDepartment + "\"]}")).andExpect(status().isUnprocessableEntity());
        // The same-org composite FKs refuse the cross-org reference even without the application checks.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id, specialty_id, is_primary,
                    created_at) VALUES (?, ?, ?, false, now())""").params(org, doctor, foreignSpecialty).update()))
                .hasMessageContaining("fk_practitioner_specialty_specialty");
    }

    @Test
    void anotherOrganizationsDoctorIsNotFound() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        mockMvc.perform(otherAdmin.apply(get(URL + "/" + doctor))).andExpect(status().isNotFound());
        mockMvc.perform(otherAdmin.apply(patch(URL + "/" + doctor)).contentType(JSON)
                .content("{\"version\":0,\"fullName\":\"Dr. Test Hijack\"}")).andExpect(status().isNotFound());
        mockMvc.perform(otherAdmin.apply(get(URL))).andExpect(jsonPath("$.totalItems", is(0)));
    }

    // --- specialties: exactly one primary ---------------------------------------------------------------------

    @Test
    void specialtiesAreReplacedAsASetWithExactlyOnePrimary() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        UUID ortho = specialty(org, "ORTHO", true);
        mockMvc.perform(json(put(URL + "/" + doctor + "/specialties"), "{\"version\":0,\"specialtyIds\":[\""
                        + medicine + "\",\"" + ortho + "\"],\"primarySpecialtyId\":\"" + ortho + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primarySpecialtyId", is(ortho.toString())))
                .andExpect(jsonPath("$.specialtyIds", containsInAnyOrder(medicine.toString(), ortho.toString())))
                .andExpect(jsonPath("$.version", is(1)));
        // Stale version.
        mockMvc.perform(json(put(URL + "/" + doctor + "/specialties"), "{\"version\":0,\"specialtyIds\":[\""
                        + medicine + "\"],\"primarySpecialtyId\":\"" + medicine + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        // No primary.
        mockMvc.perform(json(put(URL + "/" + doctor + "/specialties"), "{\"version\":1,\"specialtyIds\":[\""
                + medicine + "\"]}")).andExpect(status().isUnprocessableEntity());
        assertThat(fx.auditActions(doctor)).containsExactly("PRACTITIONER_CREATED",
                "PRACTITIONER_SPECIALTIES_CHANGED");
    }

    @Test
    void anAlreadyAssignedSpecialtyMayStayAfterItIsDeactivated() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.specialty SET active = false WHERE id = ?").param(medicine)
                .update());
        mockMvc.perform(json(put(URL + "/" + doctor + "/specialties"), "{\"version\":0,\"specialtyIds\":[\""
                        + cardiology + "\",\"" + medicine + "\"],\"primarySpecialtyId\":\"" + medicine + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void theDatabaseRefusesZeroOrTwoPrimarySpecialties() throws Exception {
        UUID doctor = registerDoctor("TEST-1001"); // cardiology primary, medicine secondary
        // Two primaries: the partial unique index refuses immediately.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                UPDATE hms.practitioner_specialty SET is_primary = true
                WHERE practitioner_id = ? AND specialty_id = ?""").params(doctor, medicine).update()))
                .hasMessageContaining("uq_practitioner_specialty_primary");
        // Zero primaries: the deferred trigger refuses at commit.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                UPDATE hms.practitioner_specialty SET is_primary = false WHERE practitioner_id = ?""")
                .param(doctor).update()))
                .rootCause().hasMessageContaining("exactly one primary specialty");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(
                "DELETE FROM hms.practitioner_specialty WHERE practitioner_id = ?").param(doctor).update()))
                .rootCause().hasMessageContaining("exactly one primary specialty");
        // A practitioner inserted without any specialty is refused at commit too.
        UUID user = doctorUser(org);
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no, created_by,
                    created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Bare', 'TEST-1009', ?, now(), now())""")
                .params(UUID.randomUUID(), org, user, user).update()))
                .rootCause().hasMessageContaining("exactly one primary specialty");
        // Moving the primary within one transaction is fine.
        fx.inOrg(org, () -> {
            jdbc.sql("UPDATE hms.practitioner_specialty SET is_primary = false WHERE practitioner_id = ?")
                    .param(doctor).update();
            return jdbc.sql("UPDATE hms.practitioner_specialty SET is_primary = true WHERE practitioner_id = ? "
                    + "AND specialty_id = ?").params(doctor, medicine).update();
        });
        mockMvc.perform(admin.apply(get(URL + "/" + doctor)))
                .andExpect(jsonPath("$.primarySpecialtyId", is(medicine.toString())));
    }

    // --- profile, departments, lifecycle ----------------------------------------------------------------------

    @Test
    void profileUpdateUsesOptimisticLockingAndAuditsFieldNames() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        mockMvc.perform(json(patch(URL + "/" + doctor), "{\"version\":0,\"fullName\":\"Dr. Test Renamed\","
                        + "\"defaultSlotMinutes\":20,\"registrationCouncil\":\"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fullName", is("Dr. Test Renamed")))
                .andExpect(jsonPath("$.defaultSlotMinutes", is(20)))
                .andExpect(jsonPath("$.registrationCouncil").doesNotExist())
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(json(patch(URL + "/" + doctor), "{\"version\":0,\"fullName\":\"Dr. Test Stale\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(json(patch(URL + "/" + doctor), "{\"fullName\":\"Dr. Test No Version\"}"))
                .andExpect(status().isUnprocessableEntity());
        String fields = jdbc.sql("""
                SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? AND action = 'PRACTITIONER_UPDATED'
                """).param(doctor).query(String.class).single();
        assertThat(fields).contains("fullName", "defaultSlotMinutes", "registrationCouncil").doesNotContain("Renamed");
    }

    @Test
    void doctorsAreDeactivatedNeverDeleted() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        mockMvc.perform(json(patch(URL + "/" + doctor), "{\"version\":0,\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("INACTIVE")));
        mockMvc.perform(admin.apply(get(URL).param("status", "ACTIVE"))).andExpect(jsonPath("$.totalItems", is(0)));
        mockMvc.perform(admin.apply(delete(URL + "/" + doctor))).andExpect(status().isMethodNotAllowed());
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.practitioner").update()))
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void departmentsAreReplacedAsASet() throws Exception {
        UUID doctor = registerDoctor("TEST-1001");
        UUID ipd = department(org, "IPD", true);
        mockMvc.perform(json(put(URL + "/" + doctor + "/departments"), "{\"version\":0,\"departmentIds\":[\""
                        + ipd + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.departmentIds", containsInAnyOrder(ipd.toString())))
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(json(put(URL + "/" + doctor + "/departments"), "{\"version\":1,\"departmentIds\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.departmentIds", hasSize(0)));
        assertThat(fx.auditActions(doctor)).containsExactly("PRACTITIONER_CREATED",
                "PRACTITIONER_DEPARTMENTS_CHANGED", "PRACTITIONER_DEPARTMENTS_CHANGED");
    }
}
