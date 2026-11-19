package com.hms.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.appointment.api.AppointmentLifecycle;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/** [E5-3] Check-in, walk-in and queue tokens (APT-3). Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class QueueTokenIT extends PostgresIT {

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

    @Autowired
    AppointmentLifecycle lifecycle;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session desk;
    private UUID doctor;
    private UUID patient;
    private int sequence;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "DESK", "appointment:create", "appointment:read", "appointment:check-in");
        desk = fx.loggedIn(org, "desk", "DESK");
        doctor = insertDoctor(org);
        patient = insertPatient(org, "HMS-000001");
    }

    @Test
    void checkInIssuesTheNextTokenOfTheDoctorAndDayAndIsAudited() throws Exception {
        UUID first = insertAppointment(doctor, patient, "SCHEDULED");
        UUID second = insertAppointment(doctor, patient, "SCHEDULED");
        String today = LocalDate.now(IST).toString();
        checkIn(first, 0).andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CHECKED_IN")))
                .andExpect(jsonPath("$.tokenNo", is(1))).andExpect(jsonPath("$.tokenDate", is(today)))
                .andExpect(jsonPath("$.version", is(1)));
        checkIn(second, 0).andExpect(status().isOk()).andExpect(jsonPath("$.tokenNo", is(2)));
        assertThat(fx.auditActions(first)).containsExactly("APPOINTMENT_CHECKED_IN");
    }

    @Test
    void aSecondCheckInIsAnInvalidTransitionAndAStaleVersionIsAConflict() throws Exception {
        UUID id = insertAppointment(doctor, patient, "SCHEDULED");
        checkIn(id, 0).andExpect(status().isOk());
        checkIn(id, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        checkIn(id, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        assertThat(maxToken(doctor)).isEqualTo(1);
        UUID cancelled = insertAppointment(doctor, patient, "CANCELLED");
        checkIn(cancelled, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
        // A refused check-in takes no number: the next one is 2.
        UUID next = insertAppointment(doctor, patient, "SCHEDULED");
        checkIn(next, 0).andExpect(jsonPath("$.tokenNo", is(2)));
    }

    @Test
    void anotherDoctorStartsAtOne() throws Exception {
        UUID otherDoctor = insertDoctor(org);
        checkIn(insertAppointment(doctor, patient, "SCHEDULED"), 0).andExpect(jsonPath("$.tokenNo", is(1)));
        checkIn(insertAppointment(doctor, patient, "SCHEDULED"), 0).andExpect(jsonPath("$.tokenNo", is(2)));
        checkIn(insertAppointment(otherDoctor, patient, "SCHEDULED"), 0).andExpect(jsonPath("$.tokenNo", is(1)));
    }

    @Test
    void tokensRestartOnANewDay() throws Exception {
        checkIn(insertAppointment(doctor, patient, "SCHEDULED"), 0).andExpect(jsonPath("$.tokenNo", is(1)));
        // Yesterday's check-in: the counter and the token both belong to the previous day now.
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.queue_token_counter SET token_date = token_date - 1"
                + " WHERE doctor_id = ?").param(doctor).update());
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.appointment SET token_date = token_date - 1"
                + " WHERE practitioner_id = ?").param(doctor).update());
        checkIn(insertAppointment(doctor, patient, "SCHEDULED"), 0).andExpect(jsonPath("$.tokenNo", is(1)));
    }

    @Test
    void aWalkInIsCheckedInAtOnceAtTheDoctorsSlotLengthOutsideAvailability() throws Exception {
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.practitioner SET default_slot_minutes = 20 WHERE id = ?")
                .param(doctor).update());
        Instant before = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        String body = mockMvc.perform(desk.apply(post(URL + "/walk-in")).contentType(JSON).content(
                        "{\"patientId\":\"" + patient + "\",\"doctorId\":\"" + doctor
                                + "\",\"reason\":\"Synthetic walk-in complaint\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.type", is("WALK_IN")))
                .andExpect(jsonPath("$.status", is("CHECKED_IN"))).andExpect(jsonPath("$.tokenNo", is(1)))
                .andExpect(jsonPath("$.tokenDate", is(LocalDate.now(IST).toString())))
                .andExpect(jsonPath("$.durationMinutes", is(20))).andExpect(jsonPath("$.version", is(0)))
                .andReturn().getResponse().getContentAsString();
        Instant start = Instant.parse(JsonPath.read(body, "$.startAt"));
        assertThat(start).isBetween(before, Instant.now().truncatedTo(ChronoUnit.MINUTES));
        assertThat(start.getEpochSecond() % 60).isZero();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        assertThat(fx.auditActions(id)).containsExactly("APPOINTMENT_BOOKED", "APPOINTMENT_CHECKED_IN");
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(rows).noneMatch(r -> r.contains("Synthetic walk-in"));
    }

    @Test
    void walkInsDoNotHoldTheDoctorsTime() throws Exception {
        walkIn(patient, doctor).andExpect(status().isCreated()).andExpect(jsonPath("$.tokenNo", is(1)));
        walkIn(patient, doctor).andExpect(status().isCreated()).andExpect(jsonPath("$.tokenNo", is(2)));
        Instant start = fx.inOrg(org, () -> jdbc.sql("SELECT start_at FROM hms.appointment WHERE type = 'WALK_IN'"
                + " LIMIT 1").query(Timestamp.class).single()).toInstant();
        // A scheduled appointment may overlap a walk-in; two scheduled ones still may not (the database decides).
        insertRaw(doctor, patient, start, start.plus(15, ChronoUnit.MINUTES), "SCHEDULED", "SCHEDULED");
        assertThatThrownBy(() -> insertRaw(doctor, patient, start.plusSeconds(60),
                start.plus(10, ChronoUnit.MINUTES), "SCHEDULED", "SCHEDULED"))
                .rootCause().hasMessageContaining("ex_appointment_practitioner_overlap");
    }

    @Test
    void aWalkInNeedsAnActiveDoctorAndAnExistingPatientOfTheOrganization() throws Exception {
        walkIn(UUID.randomUUID(), doctor).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PATIENT_NOT_AVAILABLE")));
        walkIn(patient, UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("DOCTOR_NOT_AVAILABLE")));
        UUID otherOrg = fx.organization();
        walkIn(insertPatient(otherOrg, "HMS-000001"), doctor).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PATIENT_NOT_AVAILABLE")));
        walkIn(patient, insertDoctor(otherOrg)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("DOCTOR_NOT_AVAILABLE")));
        mockMvc.perform(desk.apply(post(URL + "/walk-in")).contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        assertThat(maxToken(doctor)).isZero();
        assertThat(counterRows(doctor)).isZero(); // a refused walk-in leaves no counter behind
    }

    @Test
    void theQueueListsTodaysWaitingAndSeenPatientsInTokenOrder() throws Exception {
        UUID other = insertPatient(org, "HMS-000002");
        UUID a = insertAppointment(doctor, patient, "SCHEDULED");
        UUID b = insertAppointment(doctor, other, "SCHEDULED");
        UUID c = insertAppointment(doctor, patient, "SCHEDULED");
        insertAppointment(doctor, patient, "SCHEDULED"); // never checked in
        walkIn(other, doctor).andExpect(status().isCreated()); // token 1
        checkIn(a, 0).andExpect(status().isOk()); // token 2
        checkIn(b, 0).andExpect(status().isOk()); // token 3
        checkIn(c, 0).andExpect(status().isOk()); // token 4
        String today = LocalDate.now(IST).toString();
        mockMvc.perform(desk.apply(get("/api/v1/queue").param("doctorId", doctor.toString()).param("date", today)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.items[0].tokenNo", is(1)))
                .andExpect(jsonPath("$.items[0].patient.uhid", is("HMS-000002")))
                .andExpect(jsonPath("$.items[0].patient.name", is("Test Queue Patient")))
                .andExpect(jsonPath("$.items[0].patient.id", is(other.toString())))
                .andExpect(jsonPath("$.items[1].appointmentId", is(a.toString())))
                .andExpect(jsonPath("$.items[1].status", is("CHECKED_IN")))
                .andExpect(jsonPath("$.items[3].tokenNo", is(4)));
        // The date defaults to today; a finished visit leaves the live queue; an in-consultation one stays.
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.appointment SET status = 'COMPLETED' WHERE id = ?").param(a)
                .update());
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.appointment SET status = 'IN_CONSULTATION' WHERE id = ?")
                .param(b).update());
        mockMvc.perform(desk.apply(get("/api/v1/queue").param("doctorId", doctor.toString())))
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[1].status", is("IN_CONSULTATION")));
        mockMvc.perform(desk.apply(get("/api/v1/queue").param("doctorId", doctor.toString()).param("date",
                        LocalDate.now(IST).minusDays(1).toString())))
                .andExpect(jsonPath("$.items", hasSize(0)));
        // another organization's doctor has an empty queue here, not an error
        mockMvc.perform(desk.apply(get("/api/v1/queue").param("doctorId",
                        insertDoctor(fx.organization()).toString())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(desk.apply(get("/api/v1/queue"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
    }

    @Test
    void fiftyConcurrentCheckInsGetTokens1To50() throws Exception {
        int threads = 50;
        List<UUID> targets = new ArrayList<>();
        for (int i = 0; i < threads / 2; i++) {
            targets.add(insertAppointment(doctor, patient, "SCHEDULED"));
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Integer> tokens = new HashSet<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = n % 2 == 0
                            ? mockMvc.perform(desk.apply(post(URL + "/" + targets.get(n / 2) + "/check-in"))
                                    .contentType(JSON).content("{\"version\":0}")).andReturn().getResponse()
                            : mockMvc.perform(desk.apply(post(URL + "/walk-in")).contentType(JSON).content(
                                    "{\"patientId\":\"" + patient + "\",\"doctorId\":\"" + doctor + "\"}"))
                                    .andReturn().getResponse();
                    assertThat(response.getStatus()).isIn(200, 201);
                    return JsonPath.<Integer>read(response.getContentAsString(), "$.tokenNo");
                }));
            }
            for (Future<Integer> f : results) {
                tokens.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        Set<Integer> expected = new HashSet<>();
        for (int i = 1; i <= threads; i++) {
            expected.add(i);
        }
        assertThat(tokens).isEqualTo(expected);
        assertThat(maxToken(doctor)).isEqualTo(threads);
    }

    @Test
    void theDatabaseRefusesADuplicateTokenAndEndpointsNeedTheirPermission() throws Exception {
        UUID one = insertAppointment(doctor, patient, "SCHEDULED");
        UUID two = insertAppointment(doctor, patient, "SCHEDULED");
        checkIn(one, 0).andExpect(status().isOk());
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql("""
                UPDATE hms.appointment SET token_no = 1, token_date = (SELECT token_date FROM hms.appointment
                    WHERE id = ?) WHERE id = ?""").params(one, two).update()))
                .rootCause().hasMessageContaining("uq_appointment_token");

        fx.role(org, "READER", "appointment:read");
        var reader = fx.loggedIn(org, "reader", "READER");
        mockMvc.perform(reader.apply(post(URL + "/" + two + "/check-in")).contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(post(URL + "/walk-in")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        fx.role(org, "CHECKER", "appointment:check-in");
        var checker = fx.loggedIn(org, "checker", "CHECKER");
        mockMvc.perform(checker.apply(post(URL + "/walk-in")).contentType(JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(checker.apply(get("/api/v1/queue").param("doctorId", doctor.toString())))
                .andExpect(status().isForbidden());
        var csrf = fx.csrf();
        mockMvc.perform(post(URL + "/" + two + "/check-in").contentType(JSON).content("{\"version\":0}")
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())).andExpect(status().isUnauthorized());
        mockMvc.perform(desk.apply(post(URL + "/" + UUID.randomUUID() + "/check-in")).contentType(JSON)
                .content("{\"version\":0}")).andExpect(status().isNotFound());
        mockMvc.perform(desk.apply(post(URL + "/" + two + "/check-in")).contentType(JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void theLifecyclePortMovesACheckedInAppointmentThroughTheStateMachine() throws Exception {
        UUID id = insertAppointment(doctor, patient, "SCHEDULED");
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            lifecycle.markInConsultation(org, id);
            return null;
        })).isInstanceOf(ConflictException.class);
        checkIn(id, 0).andExpect(status().isOk());
        fx.inOrg(org, () -> {
            lifecycle.markInConsultation(org, id);
            return null;
        });
        assertThat(statusOf(id)).isEqualTo("IN_CONSULTATION");
        assertThatThrownBy(() -> fx.inOrg(org, () -> {
            lifecycle.markInConsultation(org, id);
            return null;
        })).isInstanceOf(ConflictException.class);
        fx.inOrg(org, () -> {
            lifecycle.markCompleted(org, id);
            return null;
        });
        assertThat(statusOf(id)).isEqualTo("COMPLETED");
        UUID otherOrg = fx.organization();
        assertThatThrownBy(() -> fx.inOrg(otherOrg, () -> {
            lifecycle.markCompleted(otherOrg, id);
            return null;
        })).isInstanceOf(NotFoundException.class);
    }

    // --- helpers --------------------------------------------------------------------------------------------------

    private ResultActions checkIn(UUID id, long version) throws Exception {
        return mockMvc.perform(desk.apply(post(URL + "/" + id + "/check-in")).contentType(JSON)
                .content("{\"version\":" + version + "}"));
    }

    private ResultActions walkIn(UUID who, UUID doc) throws Exception {
        return mockMvc.perform(desk.apply(post(URL + "/walk-in")).contentType(JSON)
                .content("{\"patientId\":\"" + who + "\",\"doctorId\":\"" + doc + "\"}"));
    }

    private String statusOf(UUID id) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.appointment WHERE id = ?").param(id)
                .query(String.class).single());
    }

    private int maxToken(UUID practitioner) {
        return fx.inOrg(org, () -> jdbc.sql(
                "SELECT coalesce(max(token_no), 0) FROM hms.appointment WHERE practitioner_id = ?")
                .param(practitioner).query(Integer.class).single());
    }

    private int counterRows(UUID practitioner) {
        return fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.queue_token_counter WHERE doctor_id = ?")
                .param(practitioner).query(Integer.class).single());
    }

    /** A SCHEDULED-type appointment on its own 15 minutes of next week; distinct per call, so none overlaps. */
    private UUID insertAppointment(UUID practitioner, UUID who, String status) {
        Instant start = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS)
                .plus(15L * sequence++, ChronoUnit.MINUTES);
        return insertRaw(practitioner, who, start, start.plus(15, ChronoUnit.MINUTES), status, "SCHEDULED");
    }

    private UUID insertRaw(UUID practitioner, UUID who, Instant start, Instant end, String status, String type) {
        UUID id = UUID.randomUUID();
        fx.inOrg(org, () -> jdbc.sql("""
                INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                    status, type, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())""")
                .params(id, org, who, practitioner, Timestamp.from(start), Timestamp.from(end), status, type,
                        desk.userId()).update());
        return id;
    }

    private UUID insertPatient(UUID organization, String uhid) {
        UUID id = UUID.randomUUID();
        fx.inOrg(organization, () -> jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, phone, created_by,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'Test Queue Patient', 'U', DATE '1990-01-01', '+910000000078', ?, now(), now())""")
                .params(id, organization, uhid, UUID.randomUUID()).update());
        return id;
    }

    private UUID insertDoctor(UUID organization) {
        UUID id = UUID.randomUUID();
        UUID specialty = UUID.randomUUID();
        UUID user = fx.user(organization, fx.uniqueEmail("doctor"), "DOCTOR");
        fx.inOrg(organization, () -> {
            jdbc.sql("INSERT INTO hms.specialty (id, organization_id, code, name, created_at, updated_at)"
                    + " VALUES (?, ?, ?, 'Test Medicine', now(), now())").params(specialty, organization,
                    "G" + specialty.toString().substring(0, 8).toUpperCase()).update();
            jdbc.sql("INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no,"
                    + " created_by, created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Queue', ?, ?, now(), now())")
                    .params(id, organization, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user)
                    .update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(organization, id, specialty).update();
        });
        return id;
    }
}
