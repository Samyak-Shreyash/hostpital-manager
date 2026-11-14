package com.hms.practitioner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
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
 * [E4-2] Staff records: exactly one user login per record, a user is a doctor or staff but not both, lifecycle
 * without deletes, roles left to the [E1-7] user endpoint. Synthetic data only ("Test Staff ..." names).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffRecordIT extends PostgresIT {

    private static final String URL = "/api/v1/staff";
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
    private UUID opd;

    @BeforeEach
    void organizationWithAFacilityAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        opd = department(org, "OPD", true);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private UUID department(UUID organization, String code, boolean active) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                        INSERT INTO hms.department (id, organization_id, code, name, active, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, now(), now())""")
                .params(id, organization, code, "Test " + code, active).update());
        return id;
    }

    private UUID nurseUser(UUID organization) {
        return fx.user(organization, fx.uniqueEmail("nurse"), "NURSE");
    }

    private static String body(UUID userId, String type, UUID department) {
        return "{\"userId\":\"" + userId + "\",\"fullName\":\"Test Staff Member\",\"staffType\":\"" + type
                + "\",\"designation\":\"Staff Nurse\"" + (department == null ? ""
                : ",\"departmentId\":\"" + department + "\"") + "}";
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String content) {
        return admin.apply(request).contentType(JSON).content(content);
    }

    private UUID register(String content) throws Exception {
        String response = mockMvc.perform(json(post(URL), content)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private int staffRows(UUID organization) {
        return fx.inOrg(organization, () -> jdbc.sql("SELECT count(*) FROM hms.staff").query(Integer.class).single());
    }

    private void registerDoctor(UUID user) throws Exception {
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                        INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)
                        VALUES (?, ?, 'GENMED', 'General Medicine', now(), now())""")
                .params(specialty, org).update());
        mockMvc.perform(json(post("/api/v1/doctors"), "{\"userId\":\"" + user
                        + "\",\"fullName\":\"Dr. Test Doctor\",\"registrationNo\":\"TEST-9001\",\"specialtyIds\":[\""
                        + specialty + "\"],\"primarySpecialtyId\":\"" + specialty + "\"}"))
                .andExpect(status().isCreated());
    }

    // --- authentication and authorization ---------------------------------------------------------------------

    @Test
    void endpointsNeedASession() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", opd));
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(URL + "/" + staff)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(JSON).cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .content(body(nurseUser(org), "NURSE", null))).andExpect(status().isUnauthorized());
        assertThat(staffRows(org)).isEqualTo(1);
    }

    @Test
    void eachEndpointNeedsItsPermission() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", opd));
        fx.role(org, "STF_READ", "staff:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "STF_READ");
        AdminApiFixture.Session reception = fx.loggedIn(org, "reception", "RECEPTIONIST");

        mockMvc.perform(reader.apply(get(URL + "/" + staff))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get(URL))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post(URL)).contentType(JSON).content(body(nurseUser(org), "NURSE", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch(URL + "/" + staff)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"INACTIVE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(reception.apply(get(URL + "/" + staff))).andExpect(status().isForbidden());
        assertThat(staffRows(org)).isEqualTo(1);
    }

    @Test
    void practitionerPermissionsDoNotGrantStaffAccess() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", opd));
        fx.role(org, "PRC_ALL", "practitioner:read", "practitioner:create", "practitioner:update",
                "practitioner:schedule");
        AdminApiFixture.Session practitionerAdmin = fx.loggedIn(org, "prcadmin", "PRC_ALL");
        mockMvc.perform(practitionerAdmin.apply(get(URL + "/" + staff))).andExpect(status().isForbidden());
        mockMvc.perform(practitionerAdmin.apply(get(URL))).andExpect(status().isForbidden());
        mockMvc.perform(practitionerAdmin.apply(post(URL)).contentType(JSON)
                .content(body(nurseUser(org), "NURSE", null))).andExpect(status().isForbidden());
        mockMvc.perform(practitionerAdmin.apply(patch(URL + "/" + staff)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"INACTIVE\"}")).andExpect(status().isForbidden());

        fx.role(org, "STF_ALL", "staff:read", "staff:create", "staff:update");
        AdminApiFixture.Session staffAdmin = fx.loggedIn(org, "stfadmin", "STF_ALL");
        mockMvc.perform(staffAdmin.apply(get(URL + "/" + staff))).andExpect(status().isOk());
        mockMvc.perform(staffAdmin.apply(get(URL))).andExpect(status().isOk());
        mockMvc.perform(staffAdmin.apply(post(URL)).contentType(JSON)
                .content(body(nurseUser(org), "NURSE", null))).andExpect(status().isCreated());
        mockMvc.perform(staffAdmin.apply(patch(URL + "/" + staff)).contentType(JSON)
                .content("{\"version\":0,\"designation\":\"Ward Nurse\"}")).andExpect(status().isOk());
    }

    // --- registration -----------------------------------------------------------------------------------------

    @Test
    void registrationReturnsTheDocumentedShapeAndIsAuditedWithFieldNamesOnly() throws Exception {
        UUID user = nurseUser(org);
        UUID staff = register(body(user, "NURSE", opd));
        mockMvc.perform(admin.apply(get(URL + "/" + staff))).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(user.toString())))
                .andExpect(jsonPath("$.fullName", is("Test Staff Member")))
                .andExpect(jsonPath("$.staffType", is("NURSE")))
                .andExpect(jsonPath("$.designation", is("Staff Nurse")))
                .andExpect(jsonPath("$.departmentId", is(opd.toString())))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.roles").doesNotExist());
        assertThat(fx.auditActions(staff)).containsExactly("STAFF_CREATED");
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ?")
                .param(staff).query(String.class).single();
        assertThat(fields).contains("userId", "staffType", "departmentId").doesNotContain("Test Staff", "Nurse\"");
    }

    @Test
    void theListFiltersByTypeDepartmentNameAndStatus() throws Exception {
        UUID nurse = register(body(nurseUser(org), "NURSE", opd));
        UUID clerk = register(body(nurseUser(org), "RECEPTIONIST", null));
        mockMvc.perform(admin.apply(get(URL).param("staffType", "NURSE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(1))).andExpect(jsonPath("$.items[0].id", is(nurse.toString())));
        mockMvc.perform(admin.apply(get(URL).param("departmentId", opd.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
        mockMvc.perform(admin.apply(get(URL).param("q", "staff mem")))
                .andExpect(jsonPath("$.items[*].id", containsInAnyOrder(nurse.toString(), clerk.toString())));
        mockMvc.perform(admin.apply(get(URL).param("staffType", "SURGEON")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void aUserCanBeLinkedToOnlyOneStaffRecord() throws Exception {
        UUID user = nurseUser(org);
        register(body(user, "NURSE", null));
        mockMvc.perform(json(post(URL), body(user, "OTHER", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_ALREADY_LINKED")));
        assertThat(staffRows(org)).isEqualTo(1);
    }

    @Test
    void aUserIsADoctorOrStaffButNotBoth() throws Exception {
        UUID doctorUser = nurseUser(org);
        registerDoctor(doctorUser);
        mockMvc.perform(json(post(URL), body(doctorUser, "NURSE", null)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_ALREADY_LINKED")));

        UUID staffUser = nurseUser(org);
        register(body(staffUser, "NURSE", null));
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                        INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)
                        VALUES (?, ?, 'CARDIO', 'Cardiology', now(), now())""")
                .params(specialty, org).update());
        mockMvc.perform(json(post("/api/v1/doctors"), "{\"userId\":\"" + staffUser
                        + "\",\"fullName\":\"Dr. Test Doctor\",\"registrationNo\":\"TEST-9002\",\"specialtyIds\":[\""
                        + specialty + "\"],\"primarySpecialtyId\":\"" + specialty + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("USER_ALREADY_LINKED")));
        assertThat(staffRows(org)).isEqualTo(1);
    }

    @Test
    void theDatabaseRefusesStaffForADoctorsUser() throws Exception {
        UUID user = nurseUser(org);
        registerDoctor(user);
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                        INSERT INTO hms.staff (id, organization_id, user_id, full_name, staff_type, created_by,
                            created_at, updated_at) VALUES (?, ?, ?, 'Test Staff Copy', 'NURSE', ?, now(), now())""")
                .params(UUID.randomUUID(), org, user, user).update()))
                .hasMessageContaining("already linked to a doctor or staff record");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                        INSERT INTO hms.staff (id, organization_id, user_id, full_name, staff_type, created_by,
                            created_at, updated_at) VALUES (?, ?, ?, 'Test Staff Copy', 'NURSE', ?, now(), now())""")
                .params(UUID.randomUUID(), org, UUID.randomUUID(), user).update()))
                .hasMessageContaining("fk_staff_user");
    }

    @Test
    void parallelDoctorAndStaffLinksOfOneUserCreateOneRecord() throws Exception {
        UUID user = nurseUser(org);
        UUID specialty = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                        INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)
                        VALUES (?, ?, 'GENMED', 'General Medicine', now(), now())""")
                .params(specialty, org).update());
        int threads = 4;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                MockHttpServletRequestBuilder request = i % 2 == 0 ? json(post(URL), body(user, "NURSE", null))
                        : json(post("/api/v1/doctors"), "{\"userId\":\"" + user + "\",\"fullName\":\"Dr. Test\","
                                + "\"registrationNo\":\"TEST-30" + i + "\",\"specialtyIds\":[\"" + specialty
                                + "\"],\"primarySpecialtyId\":\"" + specialty + "\"}");
                results.add(pool.submit(() -> {
                    barrier.await();
                    return mockMvc.perform(request).andReturn().getResponse().getStatus();
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsOnly(201, 409).filteredOn(s -> s == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        int total = fx.inOrg(org, () -> jdbc.sql("""
                SELECT (SELECT count(*) FROM hms.staff WHERE user_id = ?)
                     + (SELECT count(*) FROM hms.practitioner WHERE user_id = ?)""")
                .params(user, user).query(Integer.class).single());
        assertThat(total).isEqualTo(1);
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        UUID user = nurseUser(org);
        mockMvc.perform(json(post(URL), body(user, "SURGEON", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(json(post(URL), "{\"userId\":\"" + user + "\",\"fullName\":\"X\",\"staffType\":\"NURSE\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        assertThat(staffRows(org)).isZero();
    }

    @Test
    void inactiveUnknownOrOtherOrganizationReferencesAreRejectedAlike() throws Exception {
        UUID other = fx.organization();
        UUID otherUser = nurseUser(other);
        UUID otherDepartment = department(other, "OPD", true);
        UUID inactiveDepartment = department(org, "CLOSED", false);
        UUID inactiveUser = nurseUser(org);
        jdbc.sql("UPDATE hms.app_user SET status = 'INACTIVE' WHERE id = ?").param(inactiveUser).update();

        for (UUID user : List.of(otherUser, UUID.randomUUID(), inactiveUser)) {
            mockMvc.perform(json(post(URL), body(user, "NURSE", null)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("USER_NOT_AVAILABLE")));
        }
        for (UUID department : List.of(otherDepartment, UUID.randomUUID(), inactiveDepartment)) {
            mockMvc.perform(json(post(URL), body(nurseUser(org), "NURSE", department)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("DEPARTMENT_NOT_AVAILABLE")));
        }
        assertThat(staffRows(org)).isZero();
    }

    @Test
    void anotherOrganizationsStaffRecordIsNotFound() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", null));
        UUID other = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(other, "facility", "FACILITY_ADMIN");
        mockMvc.perform(otherAdmin.apply(get(URL + "/" + staff))).andExpect(status().isNotFound());
        mockMvc.perform(otherAdmin.apply(patch(URL + "/" + staff)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"INACTIVE\"}")).andExpect(status().isNotFound());
        mockMvc.perform(otherAdmin.apply(get(URL))).andExpect(jsonPath("$.totalItems", is(0)));
    }

    // --- update and lifecycle ---------------------------------------------------------------------------------

    @Test
    void updateUsesOptimisticLockingAndAuditsFieldNames() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", opd));
        mockMvc.perform(json(patch(URL + "/" + staff),
                        "{\"version\":0,\"staffType\":\"PHARMACIST\",\"designation\":\"\",\"clearDepartment\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.staffType", is("PHARMACIST")))
                .andExpect(jsonPath("$.designation", nullValue()))
                .andExpect(jsonPath("$.departmentId", nullValue()));
        mockMvc.perform(json(patch(URL + "/" + staff), "{\"version\":0,\"fullName\":\"Test Staff Renamed\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        String fields = jdbc.sql("""
                SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? AND action = 'STAFF_UPDATED'""")
                .param(staff).query(String.class).single();
        assertThat(fields).contains("staffType", "designation", "departmentId").doesNotContain("PHARMACIST");
    }

    @Test
    void staffAreDeactivatedNeverDeleted() throws Exception {
        UUID staff = register(body(nurseUser(org), "NURSE", null));
        mockMvc.perform(json(patch(URL + "/" + staff), "{\"version\":0,\"status\":\"INACTIVE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("INACTIVE")));
        mockMvc.perform(admin.apply(delete(URL + "/" + staff))).andExpect(status().isMethodNotAllowed());
        assertThat(staffRows(org)).isEqualTo(1);
        mockMvc.perform(admin.apply(get(URL).param("status", "INACTIVE"))).andExpect(jsonPath("$.totalItems", is(1)));
        // The application role has no DELETE on the table either.
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.staff").update()))
                .rootCause().hasMessageContaining("permission denied");
    }
}
