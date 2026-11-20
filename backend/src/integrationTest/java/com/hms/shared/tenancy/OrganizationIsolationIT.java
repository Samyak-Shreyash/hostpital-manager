package com.hms.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.api.Permissions;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.domain.NotFoundException;
import com.hms.support.PostgresIT;
import com.hms.support.TestOrganizations;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * [E1-6] Two-organization isolation harness (ADR-0010, CLAUDE.md section 7 "cross-organization access").
 *
 * <p>Layer 1, HTTP: every controller mapping is enumerated by reflection. A mapping with a path id must have a
 * {@link #CROSS_ORG_CASES} entry, which is called with organization B's id by a fully privileged user of
 * organization A and must answer 404 (and 2xx for A's own id, so the 404 is not for an unrelated reason).
 * A mapping without a path id must be listed in {@link #EXEMPT}. A new endpoint that is neither fails the build.
 *
 * <p>Layer 2, database: RLS hides another organization's rows even from queries that skip the app-level
 * {@code organization_id} filter, and every table with an {@code organization_id} column is under RLS unless
 * it is one of the ruled exclusions. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrganizationIsolationIT.ProbeController.class)
class OrganizationIsolationIT extends PostgresIT {

    private static final String PASSWORD = "Synthetic-Pass-123";
    private static final String SESSION_COOKIE = "HMSSESSION";
    private static final String PROBE = "/api/v1/test-isolation-probe/roles/{roleId}";

    /** Ruled exclusions from RLS (database-design.md section 14); app-level org scoping still applies to them. */
    private static final Set<String> RLS_EXCLUDED = Set.of(
            "organization", "app_user", "audit_event", "spring_session", "spring_session_attributes");

    /** Mappings without a path id: nothing to cross-check by id (list endpoints are scoped by the principal). */
    private static final Set<String> EXEMPT = Set.of(
            "POST /api/v1/auth/login",
            "GET /api/v1/appointments", // list scoped by the principal; other-org filters match nothing
            "POST /api/v1/appointments", // ids in the body; cross-org patient/doctor covered by AppointmentBookingIT
            "GET /api/v1/invoices", // list scoped by the principal; other-org patientId matches nothing
            "POST /api/v1/invoices", // ids in the body; cross-org patient/encounter answer 404, InvoiceDraftIT
            "POST /api/v1/encounters", // ids in the body; cross-org appointment/patient answer 404/422, EncounterIT
            "POST /api/v1/appointments/walk-in", // ids in the body; cross-org patient/doctor answer 422, QueueTokenIT
            "POST /api/v1/auth/logout",
            "GET /api/v1/auth/me",
            "GET /api/v1/patients",
            "POST /api/v1/patients/search-by-abha", // org-scoped search, covered by PatientAbhaIT
            "POST /api/v1/patients/duplicate-check", // org-scoped lookup, covered by PatientDuplicateIT
            "POST /api/v1/auth/change-password",
            "GET /api/v1/users",
            "GET /api/v1/audit-events", // scoped by the principal; audit_event is outside RLS (explicit predicate)
            "POST /api/v1/users",
            "POST /api/v1/users/import", // creates in the principal's organization only, like POST /users
            "GET /api/v1/roles",
            "GET /api/v1/departments", // lists are scoped by the principal
            "POST /api/v1/departments",
            "GET /api/v1/specialties",
            "POST /api/v1/specialties",
            "GET /api/v1/services",
            "POST /api/v1/services",
            "POST /api/v1/patients",
            "GET /api/v1/doctors", // list scoped by the principal
            "POST /api/v1/doctors", // referenced ids in the body: cross-org ones answer 422, PractitionerRegistrationIT
            "GET /api/v1/staff", // list scoped by the principal
            "POST /api/v1/staff", // referenced ids in the body: cross-org ones answer 422, StaffRecordIT
            "POST /api/v1/roles",
            "GET /api/v1/permissions",
            "GET /api/v1/organization", // the caller's own organization: no id in the path
            "PATCH /api/v1/organization",
            "GET /v3/api-docs",
            "GET /v3/api-docs.yaml",
            "GET /v3/api-docs/swagger-config",
            "GET /swagger-ui.html",
            "* /error");

    /** Mappings with a path id that are not organization data. Each needs a reason. */
    private static final Map<String, String> EXEMPT_WITH_ID = Map.of(
            "GET /v3/api-docs/{group}", "OpenAPI document group name, not an entity id",
            "GET /v3/api-docs.yaml/{group}", "OpenAPI document group name, not an entity id");

    /**
     * Cross-org cases, keyed "METHOD pattern". Each builds the request for a given entity of the fixture.
     * Later modules add their id-taking endpoints here (or the reflection test fails).
     */
    private static final String PATCH_BODY = "{\"version\":0,\"fullName\":\"Renamed User\"}";
    private static final String ROLE_PATCH_BODY = "{\"version\":0,\"name\":\"Renamed Role\"}";

    private static final String CATALOG_PATCH_BODY = "{\"version\":0,\"name\":\"Renamed Entry\"}";

    private static final String ENTERED_IN_ERROR_BODY = "{\"status\":\"ENTERED_IN_ERROR\"}";

    /** A free slot of the fixture doctor's Monday 09:00-10:00 window (Asia/Kolkata), always in the future. */
    private static final java.time.Instant NEXT_MONDAY_0900_IST = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
            .with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.MONDAY)).atTime(9, 0)
            .atZone(java.time.ZoneId.of("Asia/Kolkata")).toInstant();

    private static final String PRICE_BODY = "{\"amount\":\"500.00\"}";

    private static final Map<String, CrossOrgCase> CROSS_ORG_CASES = Map.ofEntries(
            Map.entry("GET " + PROBE, new CrossOrgCase(org -> get(PROBE, org.roleId()))),
            Map.entry("GET /api/v1/users/{id}",
                    new CrossOrgCase(org -> get("/api/v1/users/{id}", org.lifecycleUserId()))),
            Map.entry("PATCH /api/v1/users/{id}", new CrossOrgCase(org -> patch("/api/v1/users/{id}",
                    org.patchUserId()).contentType(MediaType.APPLICATION_JSON).content(PATCH_BODY))),
            Map.entry("POST /api/v1/users/{id}/activate",
                    new CrossOrgCase(org -> post("/api/v1/users/{id}/activate", org.lifecycleUserId()))),
            Map.entry("POST /api/v1/users/{id}/deactivate",
                    new CrossOrgCase(org -> post("/api/v1/users/{id}/deactivate", org.lifecycleUserId()))),
            Map.entry("POST /api/v1/users/{id}/reset-password",
                    new CrossOrgCase(org -> post("/api/v1/users/{id}/reset-password", org.lifecycleUserId()))),
            Map.entry("PATCH /api/v1/roles/{id}", new CrossOrgCase(org -> patch("/api/v1/roles/{id}", org.roleId())
                    .contentType(MediaType.APPLICATION_JSON).content(ROLE_PATCH_BODY))),
            Map.entry("PATCH /api/v1/departments/{id}", new CrossOrgCase(org -> patch("/api/v1/departments/{id}",
                    org.departmentId()).contentType(MediaType.APPLICATION_JSON).content(CATALOG_PATCH_BODY))),
            Map.entry("PATCH /api/v1/specialties/{id}", new CrossOrgCase(org -> patch("/api/v1/specialties/{id}",
                    org.specialtyId()).contentType(MediaType.APPLICATION_JSON).content(CATALOG_PATCH_BODY))),
            Map.entry("GET /api/v1/services/{id}",
                    new CrossOrgCase(org -> get("/api/v1/services/{id}", org.serviceId()))),
            Map.entry("PATCH /api/v1/services/{id}", new CrossOrgCase(org -> patch("/api/v1/services/{id}",
                    org.serviceId()).contentType(MediaType.APPLICATION_JSON).content(CATALOG_PATCH_BODY))),
            Map.entry("GET /api/v1/patients/{id}",
                    new CrossOrgCase(org -> get("/api/v1/patients/{id}", org.patientId()))),
            Map.entry("PATCH /api/v1/patients/{id}", new CrossOrgCase(org -> patch("/api/v1/patients/{id}",
                    org.patientId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"phone\":\"+910300009999\"}"))),
            Map.entry("GET /api/v1/patients/{patientId}/allergies",
                    new CrossOrgCase(org -> get("/api/v1/patients/{id}/allergies", org.patientId()))),
            Map.entry("POST /api/v1/patients/{patientId}/allergies", new CrossOrgCase(org -> post(
                    "/api/v1/patients/{id}/allergies", org.patientId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"substance\":\"Syntheticillin\",\"severity\":\"MILD\"}"))),
            Map.entry("PATCH /api/v1/patients/{patientId}/allergies/{entryId}", new CrossOrgCase(org -> patch(
                    "/api/v1/patients/{id}/allergies/{e}", org.patientId(), org.allergyId())
                    .contentType(MediaType.APPLICATION_JSON).content(ENTERED_IN_ERROR_BODY))),
            Map.entry("GET /api/v1/patients/{patientId}/medical-history",
                    new CrossOrgCase(org -> get("/api/v1/patients/{id}/medical-history", org.patientId()))),
            Map.entry("POST /api/v1/patients/{patientId}/medical-history", new CrossOrgCase(org -> post(
                    "/api/v1/patients/{id}/medical-history", org.patientId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"condition\":\"Syntheticosis\"}"))),
            Map.entry("PATCH /api/v1/patients/{patientId}/medical-history/{entryId}", new CrossOrgCase(org -> patch(
                    "/api/v1/patients/{id}/medical-history/{e}", org.patientId(), org.historyId())
                    .contentType(MediaType.APPLICATION_JSON).content(ENTERED_IN_ERROR_BODY))),
            // No-op bodies (unchanged values) so the shared fixture keeps version 0 whatever order cases run in.
            Map.entry("GET /api/v1/appointments/{id}",
                    new CrossOrgCase(org -> get("/api/v1/appointments/" + org.appointmentId()))),
            // Valid bodies: input is validated before the lookup, so only the other org's id can answer 404.
            Map.entry("POST /api/v1/appointments/{id}/reschedule", new CrossOrgCase(org -> post(
                    "/api/v1/appointments/{id}/reschedule", org.rescheduleAppointmentId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"startAt\":\""
                            + NEXT_MONDAY_0900_IST + "\",\"durationMinutes\":15}"))),
            Map.entry("POST /api/v1/appointments/{id}/cancel", new CrossOrgCase(org -> post(
                    "/api/v1/appointments/{id}/cancel", org.cancelAppointmentId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"reason\":\"Synthetic reason\"}"))),
            Map.entry("POST /api/v1/appointments/{id}/no-show", new CrossOrgCase(org -> post(
                    "/api/v1/appointments/{id}/no-show", org.noShowAppointmentId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0}"))),
            Map.entry("POST /api/v1/appointments/{id}/check-in", new CrossOrgCase(org -> post(
                    "/api/v1/appointments/{id}/check-in", org.checkInAppointmentId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))),
            // [E6-1] Valid bodies and the fixture's own version, so only the other org's id can answer 404.
            Map.entry("GET /api/v1/encounters/{id}",
                    new CrossOrgCase(org -> get("/api/v1/encounters/{id}", org.encounterId()))),
            Map.entry("GET /api/v1/patients/{id}/encounters",
                    new CrossOrgCase(org -> get("/api/v1/patients/{id}/encounters", org.patientId()))),
            Map.entry("PUT /api/v1/encounters/{id}/observations", new CrossOrgCase(org -> put(
                    "/api/v1/encounters/{id}/observations", org.encounterId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"observations\":[]}"))),
            Map.entry("PUT /api/v1/encounters/{id}/notes/{kind}", new CrossOrgCase(org -> put(
                    "/api/v1/encounters/{id}/notes/SYMPTOMS", org.notesEncounterId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"text\":\"Synthetic note\"}"))),
            Map.entry("PUT /api/v1/encounters/{id}/diagnoses", new CrossOrgCase(org -> put(
                    "/api/v1/encounters/{id}/diagnoses", org.diagnosesEncounterId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"diagnoses\":[]}"))),
            Map.entry("PUT /api/v1/encounters/{id}/treatment-plan", new CrossOrgCase(org -> put(
                    "/api/v1/encounters/{id}/treatment-plan", org.planEncounterId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0,\"text\":\"Synthetic plan\"}"))),
            Map.entry("PUT /api/v1/encounters/{id}/follow-up", new CrossOrgCase(org -> put(
                    "/api/v1/encounters/{id}/follow-up", org.followUpEncounterId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"date\":\"2030-01-15\"}"))),
            // [E6-2] Finalize (version 0 of the fixture) and addendum (fixture encounter is FINALIZED).
            Map.entry("POST /api/v1/encounters/{id}/finalize", new CrossOrgCase(org -> post(
                    "/api/v1/encounters/{id}/finalize", org.finalizeEncounterId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))),
            Map.entry("POST /api/v1/encounters/{id}/addenda", new CrossOrgCase(org -> post(
                    "/api/v1/encounters/{id}/addenda", org.addendumEncounterId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Synthetic addendum\"}"))),
            // [E6-3] Prescriptions: draft on an encounter; put, issue (no allergy matches), revise (an ISSUED one), read.
            Map.entry("POST /api/v1/encounters/{id}/prescriptions", new CrossOrgCase(org -> post(
                    "/api/v1/encounters/{id}/prescriptions", org.encounterId())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))),
            Map.entry("PUT /api/v1/prescriptions/{id}", new CrossOrgCase(org -> put(
                    "/api/v1/prescriptions/{id}", org.putPrescriptionId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"items\":[{\"drugName\":\"Syntheticol\",\"dose\":\"1\","
                            + "\"frequency\":\"OD\",\"durationDays\":3}]}"))),
            Map.entry("POST /api/v1/prescriptions/{id}/issue", new CrossOrgCase(org -> post(
                    "/api/v1/prescriptions/{id}/issue", org.issuePrescriptionId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))),
            Map.entry("POST /api/v1/prescriptions/{id}/revise", new CrossOrgCase(org -> post(
                    "/api/v1/prescriptions/{id}/revise", org.revisePrescriptionId())
                    .contentType(MediaType.APPLICATION_JSON).content("{}"))),
            Map.entry("GET /api/v1/prescriptions/{id}",
                    new CrossOrgCase(org -> get("/api/v1/prescriptions/{id}", org.getPrescriptionId()))),
            // [E6-5] The PDF of the (ISSUED) fixture prescription.
            Map.entry("GET /api/v1/prescriptions/{id}/pdf",
                    new CrossOrgCase(org -> get("/api/v1/prescriptions/{id}/pdf", org.getPrescriptionId()))),
            // [E7-1] Invoice drafts: read, replace items, discard (each its own DRAFT on its own encounter).
            Map.entry("GET /api/v1/invoices/{id}",
                    new CrossOrgCase(org -> get("/api/v1/invoices/{id}", org.getInvoiceId()))),
            Map.entry("PUT /api/v1/invoices/{id}/items", new CrossOrgCase(org -> put(
                    "/api/v1/invoices/{id}/items", org.putInvoiceId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"items\":[{\"description\":\"Synthetic charge\",\"quantity\":\"1\","
                            + "\"unitPrice\":\"10.00\"}]}"))),
            Map.entry("POST /api/v1/invoices/{id}/discard", new CrossOrgCase(org -> post(
                    "/api/v1/invoices/{id}/discard", org.discardInvoiceId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0}"))),
            Map.entry("GET /api/v1/queue", new CrossOrgCase(org -> get("/api/v1/queue")
                    .param("doctorId", org.practitionerId().toString()))),
            Map.entry("GET /api/v1/doctors/{id}",
                    new CrossOrgCase(org -> get("/api/v1/doctors/{id}", org.practitionerId()))),
            Map.entry("PATCH /api/v1/doctors/{id}", new CrossOrgCase(org -> patch("/api/v1/doctors/{id}",
                    org.practitionerId()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))),
            Map.entry("PUT /api/v1/doctors/{id}/specialties", new CrossOrgCase(org -> put(
                    "/api/v1/doctors/{id}/specialties", org.practitionerId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"specialtyIds\":[\"" + org.specialtyId()
                            + "\"],\"primarySpecialtyId\":\"" + org.specialtyId() + "\"}"))),
            Map.entry("PUT /api/v1/doctors/{id}/departments", new CrossOrgCase(org -> put(
                    "/api/v1/doctors/{id}/departments", org.practitionerId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"departmentIds\":[\"" + org.departmentId() + "\"]}"))),
            Map.entry("GET /api/v1/doctors/{id}/availability",
                    new CrossOrgCase(org -> get("/api/v1/doctors/{id}/availability", org.practitionerId()))),
            Map.entry("PUT /api/v1/doctors/{id}/availability", new CrossOrgCase(org -> put(
                    "/api/v1/doctors/{id}/availability", org.practitionerId()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"windows\":[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"10:00\","
                            + "\"slotMinutes\":15}]}"))), // the fixture's own window: a no-op, so the version stays 0
            Map.entry("GET /api/v1/doctors/{id}/availability-exceptions", new CrossOrgCase(org -> get(
                    "/api/v1/doctors/{id}/availability-exceptions", org.practitionerId()))),
            Map.entry("POST /api/v1/doctors/{id}/availability-exceptions", new CrossOrgCase(org -> post(
                    "/api/v1/doctors/{id}/availability-exceptions", org.practitionerId())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"date\":\"2026-11-16\"}"))),
            Map.entry("POST /api/v1/doctors/{id}/availability-exceptions/{exceptionId}/cancel",
                    new CrossOrgCase(org -> post("/api/v1/doctors/{id}/availability-exceptions/{e}/cancel",
                            org.practitionerId(), org.exceptionId()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0}"))),
            Map.entry("GET /api/v1/doctors/{id}/slots", new CrossOrgCase(org -> get(
                    "/api/v1/doctors/{id}/slots", org.practitionerId()).param("date", "2026-11-16"))),
            Map.entry("GET /api/v1/staff/{id}", new CrossOrgCase(org -> get("/api/v1/staff/{id}", org.staffId()))),
            Map.entry("PATCH /api/v1/staff/{id}", new CrossOrgCase(org -> patch("/api/v1/staff/{id}",
                    org.staffId()).contentType(MediaType.APPLICATION_JSON).content("{\"version\":0}"))),
            Map.entry("GET /api/v1/services/{id}/prices",
                    new CrossOrgCase(org -> get("/api/v1/services/{id}/prices", org.serviceId()))),
            Map.entry("POST /api/v1/services/{id}/prices", new CrossOrgCase(org -> post(
                    "/api/v1/services/{id}/prices", org.serviceId()).contentType(MediaType.APPLICATION_JSON)
                    .content(PRICE_BODY))));

    record CrossOrgCase(Function<OrgFixture, MockHttpServletRequestBuilder> request) {
    }

    /** One organization's ids that endpoints can be called with. Extend as modules add entities. */
    record OrgFixture(UUID organizationId, UUID roleId, UUID lifecycleUserId, UUID patchUserId, UUID departmentId,
            UUID specialtyId, UUID serviceId, UUID patientId, UUID allergyId, UUID historyId, UUID practitionerId,
            UUID staffId, UUID exceptionId, UUID appointmentId, UUID rescheduleAppointmentId,
            UUID cancelAppointmentId, UUID noShowAppointmentId, UUID checkInAppointmentId, UUID encounterId, UUID notesEncounterId,
            UUID diagnosesEncounterId, UUID planEncounterId, UUID followUpEncounterId, UUID finalizeEncounterId,
            UUID addendumEncounterId, UUID putPrescriptionId, UUID issuePrescriptionId, UUID revisePrescriptionId,
            UUID getPrescriptionId, UUID getInvoiceId, UUID putInvoiceId, UUID discardInvoiceId) {
    }

    /**
     * Test-only endpoint that looks a role up by id <em>without</em> an organization filter, as a buggy repository
     * would. Only RLS stands between it and organization B's role, which is exactly what it demonstrates.
     */
    @RestController
    static class ProbeController {

        private final JdbcClient jdbc;
        private final PlatformTransactionManager transactionManager;

        ProbeController(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.transactionManager = transactionManager;
        }

        @GetMapping(PROBE)
        @PreAuthorize("hasAuthority('role:read')")
        String role(@PathVariable UUID roleId) {
            return new TransactionTemplate(transactionManager).execute(status ->
                    jdbc.sql("SELECT code FROM hms.role WHERE id = ?").param(roleId).query(String.class).optional())
                    .orElseThrow(() -> new NotFoundException("Role not found."));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    OrganizationContext organizationContext;

    @Autowired
    RoleProvisioning roleProvisioning;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    private static String passwordHash;
    private OrgFixture orgA;
    private OrgFixture orgB;
    private String emailA;

    @BeforeEach
    void twoOrganizations() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        orgA = createOrganization();
        orgB = createOrganization();
        emailA = "isolation-" + UUID.randomUUID() + "@demo-clinic.example";
        UUID userA = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo Admin', ?, 'ACTIVE', false, now(), now())
                """)
                .params(userA, orgA.organizationId(), emailA, passwordHash)
                .update();
        // Every permission, so a 404 can never be a disguised authorization failure.
        inOrg(orgA.organizationId(), () -> {
            for (String permission : Permissions.ALL) {
                jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) VALUES (?, ?, ?)")
                        .params(orgA.organizationId(), orgA.roleId(), permission).update();
            }
            return jdbc.sql("INSERT INTO hms.user_role (organization_id, user_id, role_id) VALUES (?, ?, ?)")
                    .params(orgA.organizationId(), userA, orgA.roleId()).update();
        });
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- Layer 1: HTTP, reflection-driven ----------------------------------------------------------------------

    @Test
    void everyEndpointIsCoveredByACrossOrgCaseOrExplicitlyExempt() {
        List<String> unaccounted = new ArrayList<>();
        for (String key : mappingKeys()) {
            boolean takesId = key.contains("{");
            // A resource id may also come as a query parameter (GET /queue?doctorId=): a case covers it too.
            boolean accounted = CROSS_ORG_CASES.containsKey(key) || (takesId
                    ? EXEMPT_WITH_ID.containsKey(key)
                    : EXEMPT.contains(key) || EXEMPT.contains("* " + key.substring(key.indexOf(' ') + 1)));
            if (!accounted) {
                unaccounted.add(key);
            }
        }
        assertThat(unaccounted)
                .as("endpoints with a path id need a CROSS_ORG_CASES entry; endpoints without one belong in EXEMPT")
                .isEmpty();
        assertThat(mappingKeys()).as("the harness must see the probe").containsAll(CROSS_ORG_CASES.keySet());
    }

    @Test
    void anotherOrganizationsIdAnswers404OnEveryIdEndpoint() throws Exception {
        Session session = login(emailA);
        for (Map.Entry<String, CrossOrgCase> entry : CROSS_ORG_CASES.entrySet()) {
            CrossOrgCase crossOrg = entry.getValue();
            mockMvc.perform(session.apply(crossOrg.request().apply(orgA)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s with the caller's own id (control)", entry.getKey()).isBetween(200, 299));
            mockMvc.perform(session.apply(crossOrg.request().apply(orgB)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus())
                            .as("%s with another organization's id", entry.getKey()).isEqualTo(404));
        }
    }

    @Test
    void loginRunsInTheUsersOrganizationSoRlsProtectedRolesLoad() throws Exception {
        // The role tables are under RLS: permissions only load if login sets the context after the email lookup.
        Session session = login(emailA);
        mockMvc.perform(session.apply(get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .contains(Permissions.ROLE_READ));
    }

    // --- Layer 2: database -------------------------------------------------------------------------------------

    @Test
    void anotherOrganizationsRowsAreInvisibleEvenWithoutAnOrgFilter() {
        List<UUID> seenByA = inOrg(orgA.organizationId(), () ->
                jdbc.sql("SELECT DISTINCT organization_id FROM hms.role").query(UUID.class).list());
        assertThat(seenByA).containsExactly(orgA.organizationId());
        assertThat(inOrg(orgA.organizationId(), () -> jdbc.sql("SELECT count(*) FROM hms.role WHERE id = ?")
                .param(orgB.roleId()).query(Integer.class).single())).isZero();
        assertThat(inOrg(orgA.organizationId(), () -> jdbc.sql("UPDATE hms.role SET name = 'x' WHERE id = ?")
                .param(orgB.roleId()).update())).isZero();
        assertThat(inOrg(orgB.organizationId(), () -> jdbc.sql("SELECT name FROM hms.role WHERE id = ?")
                .param(orgB.roleId()).query(String.class).single())).isEqualTo("Demo Role");
    }

    @Test
    void writingARowForAnotherOrganizationIsRejected() {
        assertThatThrownBy(() -> inOrg(orgA.organizationId(), () -> jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, 'SMUGGLED', 'Smuggled', now(), now())""")
                .params(UUID.randomUUID(), orgB.organizationId()).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        assertThatThrownBy(() -> inOrg(orgA.organizationId(), () -> jdbc.sql("""
                INSERT INTO hms.document_sequence (organization_id, document_type, period_key, created_at, updated_at)
                VALUES (?, 'UHID', 'ALL', now(), now())""")
                .param(orgB.organizationId()).update()))
                .rootCause().hasMessageContaining("row-level security policy");
    }

    @Test
    void withoutAnOrganizationContextRlsTablesReturnNothing() {
        for (String table : rlsTables()) {
            int rows = new TransactionTemplate(transactionManager).execute(status ->
                    jdbc.sql("SELECT count(*) FROM hms." + table).query(Integer.class).single());
            assertThat(rows).as(table).isZero();
        }
    }

    @Test
    void everyOrgOwnedTableIsUnderForcedRlsExceptTheRuledExclusions() {
        List<String> orgOwned = jdbc.sql("""
                SELECT c.relname FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped
                WHERE n.nspname = 'hms' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                """).query(String.class).list();
        Set<String> expected = new TreeSet<>(orgOwned);
        expected.removeAll(RLS_EXCLUDED);
        assertThat(expected).contains("role", "role_permission", "user_role", "document_sequence", "department",
                "specialty", "service_catalog_item", "service_price", "patient", "patient_address",
                "patient_contact", "practitioner", "practitioner_specialty", "practitioner_department", "staff",
                "practitioner_availability", "availability_exception", "appointment", "queue_token_counter", "encounter", "encounter_observation",
                "clinical_note", "encounter_diagnosis", "treatment_plan", "follow_up", "prescription", "prescription_item",
                "invoice", "invoice_item");
        assertThat(new TreeSet<>(rlsTables())).isEqualTo(expected);
    }

    @Test
    void contextIsTransactionLocalAndDoesNotLeak() {
        inOrg(orgA.organizationId(), () -> jdbc.sql("SELECT count(*) FROM hms.role").query(Integer.class).single());
        String after = new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT coalesce(current_setting('app.organization_id', true), '')").query(String.class)
                        .single());
        assertThat(after).isEmpty();
    }

    @Test
    void authenticatedUserCannotSwitchToAnotherOrganization() {
        authenticateAs(orgA.organizationId());
        assertThat(organizationContext.current()).contains(orgA.organizationId());
        assertThatThrownBy(() -> organizationContext.runAs(orgB.organizationId(), () -> 1))
                .isInstanceOf(IllegalStateException.class);
        // The principal's own organization is applied to transactions without any explicit call.
        List<UUID> visible = new TransactionTemplate(transactionManager).execute(status ->
                jdbc.sql("SELECT DISTINCT organization_id FROM hms.role").query(UUID.class).list());
        assertThat(visible).containsExactly(orgA.organizationId());
    }

    @Test
    void systemJobsWithoutAPrincipalKeepWorking() {
        // Audit partition maintenance runs with no principal and no organization (SECURITY DEFINER function).
        assertThat(organizationContext.current()).isEmpty();
        Integer created = jdbc.sql("SELECT hms.ensure_audit_event_partitions(3)").query(Integer.class).single();
        assertThat(created).isNotNull();
    }

    // --- Fixtures ----------------------------------------------------------------------------------------------

    private OrgFixture createOrganization() {
        UUID organizationId = TestOrganizations.create(jdbc);
        roleProvisioning.provisionDefaultRoles(organizationId); // what org creation must do (Task 5 bootstrap)
        UUID roleId = UUID.randomUUID();
        inOrg(organizationId, () -> jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, 'ALL_ACCESS', 'Demo Role', now(), now())""")
                .params(roleId, organizationId).update());
        // Users other than the caller: lifecycle endpoints are idempotent so they can be called in any order;
        // the patch target is touched by the PATCH case only, so its version stays 0.
        UUID lifecycleUserId = insertUser(organizationId);
        UUID patchUserId = insertUser(organizationId);
        UUID departmentId = UUID.randomUUID();
        UUID specialtyId = UUID.randomUUID();
        UUID serviceId = UUID.randomUUID();
        UUID patientId = UUID.randomUUID();
        UUID allergyId = UUID.randomUUID();
        UUID historyId = UUID.randomUUID();
        UUID practitionerId = UUID.randomUUID();
        UUID practitionerUserId = insertUser(organizationId);
        UUID staffId = UUID.randomUUID();
        UUID staffUserId = insertUser(organizationId);
        UUID exceptionId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID rescheduleAppointmentId = UUID.randomUUID();
        UUID cancelAppointmentId = UUID.randomUUID();
        UUID noShowAppointmentId = UUID.randomUUID();
        UUID checkInAppointmentId = UUID.randomUUID();
        UUID encounterId = UUID.randomUUID();
        UUID notesEncounterId = UUID.randomUUID();
        UUID diagnosesEncounterId = UUID.randomUUID();
        UUID planEncounterId = UUID.randomUUID();
        UUID followUpEncounterId = UUID.randomUUID();
        UUID finalizeEncounterId = UUID.randomUUID();
        UUID addendumEncounterId = UUID.randomUUID();
        UUID putRx = UUID.randomUUID();
        UUID issueRx = UUID.randomUUID();
        UUID reviseRx = UUID.randomUUID();
        UUID getRx = UUID.randomUUID();
        UUID getInvoice = UUID.randomUUID();
        UUID putInvoice = UUID.randomUUID();
        UUID discardInvoice = UUID.randomUUID();
        inOrg(organizationId, () -> {
            jdbc.sql("""
                    INSERT INTO hms.department (id, organization_id, code, name, created_at, updated_at)
                    VALUES (?, ?, 'GENMED', 'General Medicine', now(), now())""")
                    .params(departmentId, organizationId).update();
            jdbc.sql("""
                    INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)
                    VALUES (?, ?, 'GENMED', 'General Medicine', now(), now())""")
                    .params(specialtyId, organizationId).update();
            jdbc.sql("""
                    INSERT INTO hms.service_catalog_item (id, organization_id, code, name, created_at, updated_at)
                    VALUES (?, ?, 'CONSULT', 'Consultation', now(), now())""")
                    .params(serviceId, organizationId).update();
            jdbc.sql("""
                    INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone,
                        created_by, created_at, updated_at)
                    VALUES (?, ?, 'HMS-000001', 'Test Patient Isolation', 'U', DATE '1990-01-01', '+910000000099',
                        ?, now(), now())""")
                    .params(patientId, organizationId, UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO hms.patient_clinical_entry (id, organization_id, patient_id, kind, subject,
                        severity, recorded_by, created_at)
                    VALUES (?, ?, ?, 'ALLERGY', 'Syntheticillin', 'MILD', ?, now())""")
                    .params(allergyId, organizationId, patientId, UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO hms.patient_clinical_entry (id, organization_id, patient_id, kind, subject,
                        recorded_by, created_at)
                    VALUES (?, ?, ?, 'HISTORY', 'Syntheticosis', ?, now())""")
                    .params(historyId, organizationId, patientId, UUID.randomUUID()).update();
            jdbc.sql("""
                    INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no,
                        created_by, created_at, updated_at)
                    VALUES (?, ?, ?, 'Dr. Test Isolation', 'TEST-0001', ?, now(), now())""")
                    .params(practitionerId, organizationId, practitionerUserId, practitionerUserId).update();
            jdbc.sql("""
                    INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id, specialty_id,
                        is_primary, created_at) VALUES (?, ?, ?, true, now())""")
                    .params(organizationId, practitionerId, specialtyId).update();
            jdbc.sql("""
                    INSERT INTO hms.staff (id, organization_id, user_id, full_name, staff_type, created_by,
                        created_at, updated_at) VALUES (?, ?, ?, 'Test Staff Isolation', 'NURSE', ?, now(), now())""")
                    .params(staffId, organizationId, staffUserId, staffUserId).update();
            jdbc.sql("""
                    INSERT INTO hms.availability_exception (id, organization_id, practitioner_id, leave_date,
                        created_by, created_at, updated_at) VALUES (?, ?, ?, DATE '2026-11-16', ?, now(), now())""")
                    .params(exceptionId, organizationId, practitionerId, practitionerUserId).update();
            jdbc.sql("""
                    INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                        created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-11-16 04:00Z', TIMESTAMPTZ '2026-11-16 04:15Z', ?, now(), now())""")
                    .params(appointmentId, organizationId, patientId, practitionerId, practitionerUserId).update();
            // One appointment per state-changing endpoint: each is consumed by its own control call.
            List<UUID> lifecycle = List.of(rescheduleAppointmentId, cancelAppointmentId, noShowAppointmentId,
                    checkInAppointmentId);
            for (int i = 0; i < lifecycle.size(); i++) {
                jdbc.sql("""
                        INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at,
                            end_at, created_by, created_at, updated_at)
                        VALUES (?, ?, ?, ?, TIMESTAMPTZ '2026-11-16 05:00Z' + make_interval(mins => ?),
                            TIMESTAMPTZ '2026-11-16 05:15Z' + make_interval(mins => ?), ?, now(), now())""")
                        .params(lifecycle.get(i), organizationId, patientId, practitionerId, i * 15, i * 15,
                                practitionerUserId).update();
            }
            // One encounter per state-changing endpoint (each own-org control call bumps its version).
            for (UUID id : List.of(encounterId, notesEncounterId, diagnosesEncounterId, planEncounterId,
                    followUpEncounterId, finalizeEncounterId)) {
                jdbc.sql("""
                        INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                            created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                        .params(id, organizationId, patientId, practitionerId, practitionerUserId).update();
            }
            // [E6-2] A FINALIZED encounter for the addendum endpoint.
            jdbc.sql("""
                    INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, status, started_at,
                        finalized_at, finalized_by, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'FINALIZED', now(), now(), ?, ?, now(), now())""")
                    .params(addendumEncounterId, organizationId, patientId, practitionerId, practitionerUserId,
                            practitionerUserId).update();
            // [E6-3] One prescription per endpoint, each on its own encounter (one live prescription per encounter).
            for (Object[] rx : new Object[][] {{putRx, "DRAFT", false}, {issueRx, "DRAFT", true},
                    {reviseRx, "ISSUED", true}, {getRx, "ISSUED", true}}) {
                UUID rxEncounter = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                            created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                        .params(rxEncounter, organizationId, patientId, practitionerId, practitionerUserId).update();
                jdbc.sql("""
                        INSERT INTO hms.prescription (id, organization_id, encounter_id, patient_id, created_by,
                            created_at, updated_at) VALUES (?, ?, ?, ?, ?, now(), now())""")
                        .params(rx[0], organizationId, rxEncounter, patientId, practitionerUserId).update();
                if ((boolean) rx[2]) {
                    jdbc.sql("""
                            INSERT INTO hms.prescription_item (id, organization_id, prescription_id, position,
                                drug_name, dose, frequency, duration_days, created_at)
                            VALUES (?, ?, ?, 0, 'Syntheticol', '1', 'OD', 3, now())""")
                            .params(UUID.randomUUID(), organizationId, rx[0]).update();
                }
                if ("ISSUED".equals(rx[1])) {
                    // Items first: the V28 guard refuses items on an issued prescription.
                    jdbc.sql("UPDATE hms.prescription SET status = 'ISSUED', issued_at = now(), issued_by = ?"
                            + " WHERE id = ?").params(practitionerUserId, rx[0]).update();
                }
            }
            // [E7-1] One DRAFT invoice per endpoint, each on its own encounter (one live invoice per encounter).
            for (UUID invoice : List.of(getInvoice, putInvoice, discardInvoice)) {
                UUID invoiceEncounter = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, started_at,
                            created_by, created_at, updated_at) VALUES (?, ?, ?, ?, now(), ?, now(), now())""")
                        .params(invoiceEncounter, organizationId, patientId, practitionerId, practitionerUserId)
                        .update();
                jdbc.sql("""
                        INSERT INTO hms.invoice (id, organization_id, type, patient_id, encounter_id, created_by,
                            created_at, updated_at) VALUES (?, ?, 'OPD', ?, ?, ?, now(), now())""")
                        .params(invoice, organizationId, patientId, invoiceEncounter, practitionerUserId).update();
            }
            jdbc.sql("""
                    INSERT INTO hms.practitioner_availability (id, organization_id, practitioner_id, weekday,
                        start_time, end_time, slot_minutes, created_at)
                    VALUES (?, ?, ?, 1, TIME '09:00', TIME '10:00', 15, now())""")
                    .params(UUID.randomUUID(), organizationId, practitionerId).update();
            return jdbc.sql("""
                    INSERT INTO hms.practitioner_department (organization_id, practitioner_id, department_id,
                        created_at) VALUES (?, ?, ?, now())""")
                    .params(organizationId, practitionerId, departmentId).update();
        });
        return new OrgFixture(organizationId, roleId, lifecycleUserId, patchUserId, departmentId, specialtyId, serviceId, patientId,
                allergyId, historyId, practitionerId, staffId, exceptionId, appointmentId, rescheduleAppointmentId,
                cancelAppointmentId, noShowAppointmentId, checkInAppointmentId, encounterId,
                notesEncounterId, diagnosesEncounterId, planEncounterId, followUpEncounterId, finalizeEncounterId,
                addendumEncounterId, putRx, issueRx, reviseRx, getRx, getInvoice, putInvoice, discardInvoice);
    }

    private UUID insertUser(UUID organizationId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo Target', ?, 'ACTIVE', false, now(), now())
                """)
                .params(id, organizationId, "target-" + id + "@demo-clinic.example", passwordHash).update();
        return id;
    }

    private <T> T inOrg(UUID organizationId, Supplier<T> work) {
        return organizationContext.runAs(organizationId,
                () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private List<String> rlsTables() {
        return jdbc.sql("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'hms' AND c.relrowsecurity AND c.relforcerowsecurity
                  AND EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
                """).query(String.class).list();
    }

    private void authenticateAs(UUID organizationId) {
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(UUID.randomUUID(), organizationId,
                "someone@demo-clinic.example", "Demo", List.of(), List.of());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    }

    private Set<String> mappingKeys() {
        Set<String> keys = new TreeSet<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            Set<String> patterns = info.getPatternValues();
            Set<String> methods = info.getMethodsCondition().getMethods().isEmpty()
                    ? Set.of("*")
                    : info.getMethodsCondition().getMethods().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toSet());
            for (String pattern : patterns) {
                for (String method : methods) {
                    keys.add(method + " " + pattern);
                }
            }
        }
        return keys;
    }

    /** A logged-in session plus the CSRF token, applied to any request. */
    private record Session(Cookie session, Cookie csrf) {
        MockHttpServletRequestBuilder apply(MockHttpServletRequestBuilder request) {
            return request.cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue());
        }
    }

    private Session login(String email) throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")
                        .cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andReturn();
        String pair = result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst().orElseThrow().split(";", 2)[0];
        return new Session(new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1)), csrf);
    }
}
