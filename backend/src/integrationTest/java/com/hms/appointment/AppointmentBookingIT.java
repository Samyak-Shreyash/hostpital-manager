package com.hms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E5-1] Booking (APT-1, BR-A1, BR-A2). The doctor works Mondays 09:00-10:00 Asia/Kolkata in 15-minute slots; the
 * tests book on the next Monday so the times are always in the future. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AppointmentBookingIT extends PostgresIT {

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
        fx.role(org, "DESK", "appointment:create", "appointment:read", "practitioner:schedule");
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
    void bookingReturnsTheAppointmentAndIsAuditedWithoutTheReason() throws Exception {
        String body = mockMvc.perform(desk.apply(post(URL)).contentType(JSON)
                        .content(booking(patient, at("09:15"), 15, "Synthetic complaint text")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.patientId", is(patient.toString())))
                .andExpect(jsonPath("$.doctorId", is(doctor.toString())))
                .andExpect(jsonPath("$.startAt", is(at("09:15").toString())))
                .andExpect(jsonPath("$.endAt", is(at("09:30").toString())))
                .andExpect(jsonPath("$.durationMinutes", is(15)))
                .andExpect(jsonPath("$.type", is("SCHEDULED")))
                .andExpect(jsonPath("$.status", is("SCHEDULED")))
                .andExpect(jsonPath("$.outsideAvailability", is(false)))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.patient.id", is(patient.toString())))
                .andExpect(jsonPath("$.patient.uhid", is("HMS-000001")))
                .andExpect(jsonPath("$.patient.name", is("Test Booking Patient")))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

        mockMvc.perform(desk.apply(get(URL + "/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.reason", is("Synthetic complaint text")))
                .andExpect(jsonPath("$.patient.uhid", is("HMS-000001")));
        assertThat(fx.auditActions(id)).containsExactly("APPOINTMENT_BOOKED", "APPOINTMENT_VIEWED");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(rows).noneMatch(r -> r.contains("Synthetic complaint"));
    }

    @Test
    void fiftyConcurrentOverlappingBookingsLeaveExactlyOne() throws Exception {
        int threads = 50;
        List<UUID> patients = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            patients.add(insertPatient(org, String.format("HMS-%06d", 100 + i)));
        }
        Instant start = at("09:30");
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                UUID who = patients.get(i);
                // Half book 09:30-09:45, half 09:30-10:00: every pair overlaps.
                int minutes = i % 2 == 0 ? 15 : 30;
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(desk.apply(post(URL)).contentType(JSON)
                            .content(booking(who, start, minutes, null))).andReturn().getResponse();
                    if (response.getStatus() == 409) {
                        assertThat((Object) JsonPath.read(response.getContentAsString(), "$.code"))
                                .isEqualTo("SLOT_CONFLICT");
                    }
                    return response.getStatus();
                }));
            }
            for (Future<Integer> f : results) {
                statuses.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);
        assertThat(appointmentRows(doctor)).isEqualTo(1);
    }

    @Test
    void anOverlappingBookingIsASlotConflictButAnAdjacentOneIsFine() throws Exception {
        book(patient, at("09:00"), 30).andExpect(status().isCreated());
        book(patient, at("09:15"), 15).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SLOT_CONFLICT")));
        book(patient, at("09:30"), 15).andExpect(status().isCreated());
    }

    @Test
    void theDatabaseRejectsOverlapsEvenWithoutTheServiceButIgnoresCancelledOnes() {
        UUID first = insertRaw(at("09:00"), at("09:30"), "SCHEDULED");
        assertThatThrownBy(() -> insertRaw(at("09:15"), at("09:45"), "SCHEDULED"))
                .rootCause().hasMessageContaining("ex_appointment_practitioner_overlap");
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.appointment SET status = 'CANCELLED' WHERE id = ?").param(first)
                .update());
        insertRaw(at("09:15"), at("09:45"), "SCHEDULED");
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("DELETE FROM hms.appointment WHERE id = ?")
                .param(first).update())).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void theTimeMustBeAFreeSlotOfTheDoctorsAvailability() throws Exception {
        book(patient, at("08:45"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY"))); // before working hours
        book(patient, at("09:50"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY"))); // runs past 10:00
        book(patient, at("09:05"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY"))); // off the 15-minute grid
        book(patient, at("09:00"), 20).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY"))); // not a whole number of slots
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.availability_exception (id, organization_id, practitioner_id, leave_date, start_time,
                    end_time, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, TIME '09:00', TIME '09:30', ?, now(), now())""")
                .params(UUID.randomUUID(), org, doctor, monday, desk.userId()).update());
        book(patient, at("09:15"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("OUTSIDE_AVAILABILITY"))); // on leave
    }

    @Test
    void overrideBooksOutsideAvailabilityOnlyWithThePermissionAndIsAudited() throws Exception {
        String outside = booking(patient, at("18:00"), 20, null).replace("}", ",\"outsideAvailability\":true}");
        mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content(outside)).andExpect(status().isForbidden());

        fx.role(org, "OVERRIDER", "appointment:create", "appointment:override");
        var overrider = fx.loggedIn(org, "overrider", "OVERRIDER");
        String body = mockMvc.perform(overrider.apply(post(URL)).contentType(JSON).content(outside))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outsideAvailability", is(true)))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(fx.auditActions(id)).containsExactly("APPOINTMENT_BOOKED", "APPOINTMENT_AVAILABILITY_OVERRIDDEN");
    }

    @Test
    void aPastStartNeedsBackdate() throws Exception {
        Instant past = LocalDate.now(IST).minusDays(1).atTime(18, 0).atZone(IST).toInstant();
        String body = booking(patient, past, 15, null).replace("}", ",\"outsideAvailability\":true}");
        fx.role(org, "OVERRIDER", "appointment:create", "appointment:override");
        var overrider = fx.loggedIn(org, "overrider", "OVERRIDER");
        mockMvc.perform(overrider.apply(post(URL)).contentType(JSON).content(body))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("START_IN_PAST")));

        fx.role(org, "BACKDATER", "appointment:create", "appointment:override", "appointment:backdate");
        var backdater = fx.loggedIn(org, "backdater", "BACKDATER");
        mockMvc.perform(backdater.apply(post(URL)).contentType(JSON).content(body)).andExpect(status().isCreated());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        book(patient, at("09:00").plusSeconds(30), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        book(patient, at("09:00"), 0).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(desk.apply(post(URL)).contentType(JSON)
                        .content(booking(patient, at("09:00"), 15, "x".repeat(501))))
                .andExpect(status().isUnprocessableEntity());
        book(UUID.randomUUID(), at("09:00"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PATIENT_NOT_AVAILABLE")));
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.practitioner SET status = 'INACTIVE' WHERE id = ?").param(doctor)
                .update());
        book(patient, at("09:00"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("DOCTOR_NOT_AVAILABLE")));
        assertThat(appointmentRows(doctor)).isZero();
    }

    @Test
    void anotherOrganizationsPatientOrDoctorCannotBeBookedAndItsAppointmentIsNotFound() throws Exception {
        UUID otherOrg = fx.organization();
        UUID otherPatient = insertPatient(otherOrg, "HMS-000001");
        UUID otherDoctor = insertDoctor(otherOrg);
        book(otherPatient, at("09:00"), 15).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PATIENT_NOT_AVAILABLE")));
        mockMvc.perform(desk.apply(post(URL)).contentType(JSON)
                        .content(booking(patient, at("09:00"), 15, null).replace(doctor.toString(),
                                otherDoctor.toString())))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("DOCTOR_NOT_AVAILABLE")));

        UUID otherAppointment = UUID.randomUUID();
        fx.inOrg(otherOrg, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(otherAppointment, otherOrg, otherPatient, otherDoctor, java.sql.Timestamp.from(at("09:00")),
                        java.sql.Timestamp.from(at("09:15")), UUID.randomUUID()).update());
        mockMvc.perform(desk.apply(get(URL + "/" + otherAppointment))).andExpect(status().isNotFound());
        mockMvc.perform(desk.apply(get(URL + "/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void endpointsNeedASessionAndTheirPermission() throws Exception {
        var csrf = fx.csrf();
        mockMvc.perform(post(URL).contentType(JSON).content(booking(patient, at("09:00"), 15, null))
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(URL + "/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        fx.role(org, "READER", "appointment:read");
        var reader = fx.loggedIn(org, "reader", "READER");
        mockMvc.perform(reader.apply(post(URL)).contentType(JSON).content(booking(patient, at("09:00"), 15, null)))
                .andExpect(status().isForbidden());
        fx.role(org, "BOOKER", "appointment:create");
        var booker = fx.loggedIn(org, "booker", "BOOKER");
        mockMvc.perform(booker.apply(post(URL)).contentType(JSON).content(booking(patient, at("09:00"), 15, null)))
                .andExpect(status().isCreated());
        UUID id = fx.inOrg(org, () -> jdbc.sql("SELECT id FROM hms.appointment WHERE practitioner_id = ?")
                .param(doctor).query(UUID.class).single());
        mockMvc.perform(booker.apply(get(URL + "/" + id))).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(get(URL + "/" + id))).andExpect(status().isOk());
    }

    @Test
    void freeSlotsLeaveOutBookedTimes() throws Exception {
        String slots = "/api/v1/doctors/" + doctor + "/slots";
        mockMvc.perform(desk.apply(get(slots).param("date", monday.toString()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.slots", hasSize(4)));
        book(patient, at("09:15"), 30).andExpect(status().isCreated());
        mockMvc.perform(desk.apply(get(slots).param("date", monday.toString())))
                .andExpect(jsonPath("$.slots", hasSize(2)))
                .andExpect(jsonPath("$.slots[0].startAt", is(at("09:00").toString())))
                .andExpect(jsonPath("$.slots[1].startAt", is(at("09:45").toString())));
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.appointment SET status = 'CANCELLED' WHERE practitioner_id = ?")
                .param(doctor).update());
        mockMvc.perform(desk.apply(get(slots).param("date", monday.toString())))
                .andExpect(jsonPath("$.slots", hasSize(4)));
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private Instant at(String localTime) {
        return monday.atTime(LocalTime.parse(localTime)).atZone(IST).toInstant();
    }

    private org.springframework.test.web.servlet.ResultActions book(UUID who, Instant start, int minutes)
            throws Exception {
        return mockMvc.perform(desk.apply(post(URL)).contentType(JSON).content(booking(who, start, minutes, null)));
    }

    private String booking(UUID who, Instant start, int minutes, String reason) {
        return "{\"patientId\":\"" + who + "\",\"doctorId\":\"" + doctor + "\",\"startAt\":\"" + start
                + "\",\"durationMinutes\":" + minutes + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + "}";
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

    private int appointmentRows(UUID practitioner) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.appointment WHERE practitioner_id = ?")
                .param(practitioner).query(Integer.class).single());
    }

    private UUID insertPatient(UUID organization, String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Booking Patient', 'U', DATE '1990-01-01', '+910000000077', ?, now(), now())""")
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
                    + " created_by, created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Booking', ?, ?, now(), now())")
                    .params(id, organization, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user)
                    .update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(organization, id, specialty).update();
        });
        return id;
    }
}
