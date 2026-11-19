package com.hms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
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
 * [E5-2] Reschedule, cancel, no-show and listing (domain-model.md section 6). The doctor works Mondays 09:00-10:00
 * Asia/Kolkata in 15-minute slots; the tests use the next Monday so times are in the future. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentLifecycleIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String URL = "/api/v1/appointments";
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
    private AdminApiFixture.Session desk;
    private UUID doctor;
    private UUID patient;
    private LocalDate monday;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "DESK", "appointment:create", "appointment:read", "appointment:update", "appointment:cancel",
                "practitioner:schedule");
        desk = fx.loggedIn(org, "desk", "DESK");
        doctor = insertDoctor(org);
        patient = insertPatient(org, "HMS-000001");
        monday = LocalDate.now(IST).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        mockMvc.perform(desk.apply(put("/api/v1/doctors/" + doctor + "/availability")).contentType(JSON)
                        .content("{\"version\":0,\"windows\":[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"10:00\","
                                + "\"slotMinutes\":15}]}"))
                .andExpect(status().isOk());
    }

    @Test
    void rescheduleMarksTheOldOneAndCreatesALinkedNewOne() throws Exception {
        UUID old = bookId(at("09:00"), 15, "Synthetic complaint text");
        String body = reschedule(old, 0, at("09:30"), 15).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.rescheduledFromId", is(old.toString())))
                .andExpect(jsonPath("$.startAt", is(at("09:30").toString())))
                .andExpect(jsonPath("$.reason", is("Synthetic complaint text")))
                .andExpect(jsonPath("$.version", is(0)))
                .andReturn().getResponse().getContentAsString();
        UUID fresh = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(desk.apply(get(URL + "/" + old))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RESCHEDULED"))).andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.rescheduledFromId", nullValue()));
        assertThat(fx.auditActions(old)).contains("APPOINTMENT_RESCHEDULED");
        assertThat(fx.auditActions(fresh)).containsExactly("APPOINTMENT_BOOKED");
        // the old time is free again
        book(patient, at("09:00"), 15).andExpect(status().isCreated());
    }

    @Test
    void theNewTimeMayOverlapTheOldOneAndMustObeyConflictsAndAvailability() throws Exception {
        UUID first = bookId(at("09:00"), 30, null);
        reschedule(first, 0, at("09:15"), 30).andExpect(status().isCreated());

        UUID blocker = bookId(at("09:45"), 15, null);
        UUID movable = bookId(at("09:00"), 15, null);
        reschedule(movable, 0, at("09:45"), 15).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SLOT_CONFLICT")));
        assertThat(statusOf(movable)).isEqualTo("SCHEDULED"); // rolled back as a whole
        assertThat(statusOf(blocker)).isEqualTo("SCHEDULED");

        reschedule(movable, 0, at("11:00"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY")));
        assertThat(statusOf(movable)).isEqualTo("SCHEDULED");
    }

    @Test
    void anAlreadyRescheduledAppointmentCannotBeRescheduledAgain() throws Exception {
        UUID old = bookId(at("09:00"), 15, null);
        reschedule(old, 0, at("09:30"), 15).andExpect(status().isCreated());
        reschedule(old, 1, at("09:45"), 15).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void cancelNeedsAReasonAndIsAuditedWithoutIt() throws Exception {
        UUID id = bookId(at("09:00"), 15, null);
        cancel(id, 0, null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        cancel(id, 0, "   ").andExpect(status().isUnprocessableEntity());
        cancel(id, 0, "x".repeat(501)).andExpect(status().isUnprocessableEntity());
        assertThat(statusOf(id)).isEqualTo("SCHEDULED");

        cancel(id, 0, "Synthetic cancel reason").andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CANCELLED")))
                .andExpect(jsonPath("$.cancelReason", is("Synthetic cancel reason")))
                .andExpect(jsonPath("$.version", is(1)));
        assertThat(fx.auditActions(id)).contains("APPOINTMENT_CANCELLED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(rows).noneMatch(r -> r.contains("Synthetic cancel reason"));
        book(patient, at("09:00"), 15).andExpect(status().isCreated()); // time freed
    }

    @Test
    void cancellingACheckedInAppointmentIsAllowedButACompletedOneIsNot() throws Exception {
        UUID checkedIn = insertRaw(at("09:00"), at("09:15"), "CHECKED_IN");
        cancel(checkedIn, 0, "Synthetic").andExpect(status().isOk());
        UUID completed = insertRaw(at("09:15"), at("09:30"), "COMPLETED");
        cancel(completed, 0, "Synthetic").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        UUID inConsultation = insertRaw(at("09:30"), at("09:45"), "IN_CONSULTATION");
        cancel(inConsultation, 0, "Synthetic").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        assertThat(statusOf(completed)).isEqualTo("COMPLETED");
    }

    @Test
    void aStaleVersionIsAConcurrentModification() throws Exception {
        UUID id = bookId(at("09:00"), 15, null);
        cancel(id, 5, "Synthetic").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        reschedule(id, 5, at("09:30"), 15).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        noShow(id, 5).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        cancel(id, 0, "Synthetic").andExpect(status().isOk());
        cancel(id, 0, "Synthetic").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
    }

    @Test
    void noShowFreesTheTimeAndIsTerminal() throws Exception {
        UUID id = bookId(at("09:00"), 15, null);
        noShow(id, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("NO_SHOW")))
                .andExpect(jsonPath("$.version", is(1)));
        assertThat(fx.auditActions(id)).contains("APPOINTMENT_NO_SHOW");
        noShow(id, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        book(patient, at("09:00"), 15).andExpect(status().isCreated());
        UUID checkedIn = insertRaw(at("09:15"), at("09:30"), "CHECKED_IN");
        noShow(checkedIn, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void eachActionNeedsItsPermission() throws Exception {
        UUID id = bookId(at("09:00"), 15, null);
        fx.role(org, "NOCANCEL", "appointment:read", "appointment:update");
        var noCancel = fx.loggedIn(org, "nocancel", "NOCANCEL");
        mockMvc.perform(noCancel.apply(post(URL + "/" + id + "/cancel")).contentType(JSON)
                        .content("{\"version\":0,\"reason\":\"Synthetic\"}"))
                .andExpect(status().isForbidden());
        fx.role(org, "CANCELONLY", "appointment:read", "appointment:cancel");
        var cancelOnly = fx.loggedIn(org, "cancelonly", "CANCELONLY");
        mockMvc.perform(cancelOnly.apply(post(URL + "/" + id + "/no-show")).contentType(JSON)
                        .content("{\"version\":0}")).andExpect(status().isForbidden());
        mockMvc.perform(cancelOnly.apply(post(URL + "/" + id + "/reschedule")).contentType(JSON)
                        .content("{\"version\":0,\"startAt\":\"" + at("09:30") + "\",\"durationMinutes\":15}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(cancelOnly.apply(post(URL + "/" + id + "/cancel")).contentType(JSON)
                        .content("{\"version\":0,\"reason\":\"Synthetic\"}")).andExpect(status().isOk());
        fx.role(org, "NOREAD", "appointment:create");
        var noRead = fx.loggedIn(org, "noread", "NOREAD");
        mockMvc.perform(noRead.apply(get(URL))).andExpect(status().isForbidden());
        var csrf = fx.csrf();
        mockMvc.perform(post(URL + "/" + id + "/cancel").contentType(JSON)
                        .content("{\"version\":0,\"reason\":\"Synthetic\"}").cookie(csrf)
                        .header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void anotherOrganizationsAppointmentIsNotFound() throws Exception {
        UUID otherOrg = fx.organization();
        UUID otherPatient = insertPatient(otherOrg, "HMS-000001");
        UUID otherDoctor = insertDoctor(otherOrg);
        UUID other = UUID.randomUUID();
        fx.inOrg(otherOrg, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(other, otherOrg, otherPatient, otherDoctor, java.sql.Timestamp.from(at("09:00")),
                        java.sql.Timestamp.from(at("09:15")), UUID.randomUUID()).update());
        reschedule(other, 0, at("09:30"), 15).andExpect(status().isNotFound());
        cancel(other, 0, "Synthetic").andExpect(status().isNotFound());
        noShow(other, 0).andExpect(status().isNotFound());
        reschedule(UUID.randomUUID(), 0, at("09:30"), 15).andExpect(status().isNotFound());
        mockMvc.perform(desk.apply(get(URL).param("patientId", otherPatient.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void listFiltersByDoctorPatientDateAndStatusAndPages() throws Exception {
        UUID second = insertPatient(org, "HMS-000002");
        bookId(at("09:00"), 15, null);
        UUID b = bookId(at("09:15"), 15, null);
        mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content(booking(second, at("09:30"), 15)))
                .andExpect(status().isCreated());
        cancel(b, 0, "Synthetic").andExpect(status().isOk());

        mockMvc.perform(desk.apply(get(URL).param("doctorId", doctor.toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems", is(3)))
                .andExpect(jsonPath("$.page", is(0))).andExpect(jsonPath("$.size", is(20)))
                .andExpect(jsonPath("$.items[0].startAt", is(at("09:00").toString())))
                .andExpect(jsonPath("$.items[2].startAt", is(at("09:30").toString())));
        mockMvc.perform(desk.apply(get(URL).param("patientId", second.toString())))
                .andExpect(jsonPath("$.totalItems", is(1)));
        mockMvc.perform(desk.apply(get(URL).param("status", "CANCELLED")))
                .andExpect(jsonPath("$.totalItems", is(1))).andExpect(jsonPath("$.items[0].id", is(b.toString())));
        mockMvc.perform(desk.apply(get(URL).param("date", monday.toString())))
                .andExpect(jsonPath("$.totalItems", is(3)));
        mockMvc.perform(desk.apply(get(URL).param("date", monday.plusDays(1).toString())))
                .andExpect(jsonPath("$.totalItems", is(0)));
        mockMvc.perform(desk.apply(get(URL).param("doctorId", doctor.toString()).param("size", "2")
                        .param("page", "1")))
                .andExpect(jsonPath("$.items", hasSize(1))).andExpect(jsonPath("$.totalItems", is(3)))
                .andExpect(jsonPath("$.items[0].startAt", is(at("09:30").toString())));
        mockMvc.perform(desk.apply(get(URL).param("status", "BOGUS"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(desk.apply(get(URL).param("size", "101"))).andExpect(status().isUnprocessableEntity());
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private Instant at(String localTime) {
        return monday.atTime(LocalTime.parse(localTime)).atZone(IST).toInstant();
    }

    private String booking(UUID who, Instant start, int minutes) {
        return "{\"patientId\":\"" + who + "\",\"doctorId\":\"" + doctor + "\",\"startAt\":\"" + start
                + "\",\"durationMinutes\":" + minutes + "}";
    }

    private ResultActions book(UUID who, Instant start, int minutes) throws Exception {
        return mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content(booking(who, start, minutes)));
    }

    private UUID bookId(Instant start, int minutes, String reason) throws Exception {
        String body = booking(patient, start, minutes);
        if (reason != null) {
            body = body.replace("}", ",\"reason\":\"" + reason + "\"}");
        }
        String response = mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private ResultActions reschedule(UUID id, long version, Instant start, int minutes) throws Exception {
        return mockMvc.perform(desk.apply(post(URL + "/" + id + "/reschedule")).contentType(JSON)
                .content("{\"version\":" + version + ",\"startAt\":\"" + start + "\",\"durationMinutes\":" + minutes
                        + "}"));
    }

    private ResultActions cancel(UUID id, long version, String reason) throws Exception {
        return mockMvc.perform(desk.apply(post(URL + "/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":" + version + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}"));
    }

    private ResultActions noShow(UUID id, long version) throws Exception {
        return mockMvc.perform(desk.apply(post(URL + "/" + id + "/no-show")).contentType(JSON)
                .content("{\"version\":" + version + "}"));
    }

    private String statusOf(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.appointment WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private UUID insertRaw(Instant start, Instant end, String status) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(id, org, patient, doctor, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end),
                        status, desk.userId()).update());
        return id;
    }

    private UUID insertPatient(UUID organization, String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Lifecycle Patient', 'U', DATE '1990-01-01', '+910000000078', ?, now(), now())""")
                .params(id, organization, uhid, UUID.randomUUID()).update());
        return id;
    }

    private UUID insertDoctor(UUID organization) {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        UUID user = fx.user(organization, fx.uniqueEmail("doctor"), "DOCTOR");
        fx.inOrg(organization, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, 'GENMED', 'Test Medicine', now(), now())").params(specialty, organization)
                    .update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no,"
                    + " created_by, created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Lifecycle', ?, ?, now(), now())")
                    .params(id, organization, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user)
                    .update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(organization, id, specialty).update();
        });
        return id;
    }
}
