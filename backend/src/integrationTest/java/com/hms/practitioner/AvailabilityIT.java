package com.hms.practitioner;

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
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.transaction.PlatformTransactionManager;

/** [E4-3] Weekly availability, leave and free slots. Synthetic data only. 2026-11-16 is a Monday. */
@SpringBootTest
@AutoConfigureMockMvc
class AvailabilityIT extends PostgresIT {

    private static final String JSON = MediaType.APPLICATION_JSON_VALUE;
    private static final String MONDAY = "2026-11-16";
    private static final String MON_9_TO_10 = "[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"10:00\"}]";

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
    PractitionerAvailability port;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session scheduler;
    private UUID doctor;
    private String url;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        fx.role(org, "SCHEDULER", "practitioner:read", "practitioner:schedule", "appointment:read");
        scheduler = fx.loggedIn(org, "scheduler", "SCHEDULER");
        doctor = insertDoctor(org);
        url = "/api/v1/doctors/" + doctor;
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
                    + " created_by, created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Slots', ?, ?, now(), now())")
                    .params(id, organization, user, "TEST-" + id.toString().substring(0, 8).toUpperCase(), user)
                    .update();
            return jdbc.sql("INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id,"
                    + " specialty_id, is_primary, created_at) VALUES (?, ?, ?, true, now())")
                    .params(organization, id, specialty).update();
        });
        return id;
    }

    private void template(String windows) throws Exception {
        long version = fx.inOrg(org, () -> jdbc.sql("SELECT version FROM hms.practitioner WHERE id = ?")
                .param(doctor).query(Long.class).single());
        mockMvc.perform(scheduler.apply(put(url + "/availability")).contentType(JSON)
                .content("{\"version\":" + version + ",\"windows\":" + windows + "}")).andExpect(status().isOk());
    }

    @Test
    void templateIsReplacedVersionedAndAudited() throws Exception {
        mockMvc.perform(scheduler.apply(put(url + "/availability")).contentType(JSON)
                        .content("{\"version\":0,\"windows\":" + MON_9_TO_10 + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)))
                .andExpect(jsonPath("$.timezone", is("Asia/Kolkata")))
                .andExpect(jsonPath("$.windows[0].start", is("09:00")))
                .andExpect(jsonPath("$.windows[0].slotMinutes", is(15)));
        mockMvc.perform(scheduler.apply(put(url + "/availability")).contentType(JSON)
                        .content("{\"version\":0,\"windows\":[]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(scheduler.apply(put(url + "/availability")).contentType(JSON)
                        .content("{\"version\":1,\"windows\":[{\"weekday\":1,\"start\":\"09:00\",\"end\":\"10:00\"},"
                                + "{\"weekday\":1,\"start\":\"09:45\",\"end\":\"11:00\"}]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("AVAILABILITY_OVERLAP")));
        String fields = jdbc.sql("SELECT changed_fields::text FROM hms.audit_event WHERE entity_id = ? AND action ="
                + " 'PRACTITIONER_AVAILABILITY_CHANGED'").param(doctor).query(String.class).single();
        assertThat(fields).isEqualTo("{windows}");
    }

    @Test
    void overlappingWindowsAreRejectedByTheDatabase() {
        String insert = "INSERT INTO hms.practitioner_availability (id, organization_id, practitioner_id, weekday,"
                + " start_time, end_time, slot_minutes, created_at) VALUES (?, ?, ?, 1, ?::time, ?::time, 15, now())";
        fx.inOrg(org, () -> jdbc.sql(insert).params(UUID.randomUUID(), org, doctor, "09:00", "10:00").update());
        // Adjacent windows are allowed.
        fx.inOrg(org, () -> jdbc.sql(insert).params(UUID.randomUUID(), org, doctor, "10:00", "11:00").update());
        assertThatThrownBy(() -> fx.inOrg(org, () -> jdbc.sql(insert)
                .params(UUID.randomUUID(), org, doctor, "09:30", "09:45").update()))
                .hasMessageContaining("ex_practitioner_availability_overlap");
    }

    @Test
    void slotsAreUtcInstantsInTheOrganizationTimezone() throws Exception {
        template(MON_9_TO_10);
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("date", MONDAY)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots", hasSize(4)))
                .andExpect(jsonPath("$.slots[0].startAt", is("2026-11-16T03:30:00Z")))
                .andExpect(jsonPath("$.slots[3].endAt", is("2026-11-16T04:30:00Z")));
        // A zone with DST: the same 09:00 local is 14:00Z before and 13:00Z after 2026-03-08.
        jdbc.sql("UPDATE hms.organization SET timezone = 'America/New_York' WHERE id = ?").param(org).update();
        String body = mockMvc.perform(scheduler.apply(get(url + "/slots").param("from", "2026-03-02")
                        .param("to", "2026-03-09")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots", hasSize(8)))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.slots[0].startAt")).isEqualTo("2026-03-02T14:00:00Z");
        assertThat((String) JsonPath.read(body, "$.slots[4].startAt")).isEqualTo("2026-03-09T13:00:00Z");
    }

    @Test
    void leaveRemovesSlotsWholeDayAndPartialAndCanBeCancelled() throws Exception {
        template(MON_9_TO_10);
        String partial = mockMvc.perform(scheduler.apply(post(url + "/availability-exceptions")).contentType(JSON)
                        .content("{\"date\":\"" + MONDAY + "\",\"start\":\"09:20\",\"end\":\"09:40\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.wholeDay", is(false)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("date", MONDAY)))
                .andExpect(jsonPath("$.slots", hasSize(2)))
                .andExpect(jsonPath("$.slots[1].startAt", is("2026-11-16T04:15:00Z")));
        String whole = mockMvc.perform(scheduler.apply(post(url + "/availability-exceptions")).contentType(JSON)
                        .content("{\"date\":\"" + MONDAY + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.wholeDay", is(true)))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("date", MONDAY)))
                .andExpect(jsonPath("$.slots", hasSize(0)));
        String wholeId = JsonPath.read(whole, "$.id");
        mockMvc.perform(scheduler.apply(post(url + "/availability-exceptions/" + wholeId + "/cancel"))
                        .contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status", is("CANCELLED")));
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("date", MONDAY)))
                .andExpect(jsonPath("$.slots", hasSize(2)));
        mockMvc.perform(scheduler.apply(get(url + "/availability-exceptions")))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(scheduler.apply(post(url + "/availability-exceptions")).contentType(JSON)
                        .content("{\"date\":\"" + MONDAY + "\",\"start\":\"09:20\"}"))
                .andExpect(status().isUnprocessableEntity());
        assertThat(fx.auditActions(UUID.fromString(JsonPath.read(partial, "$.id"))))
                .containsExactly("AVAILABILITY_EXCEPTION_CREATED");
        assertThat(fx.auditActions(UUID.fromString(wholeId)))
                .contains("AVAILABILITY_EXCEPTION_CREATED", "AVAILABILITY_EXCEPTION_CANCELLED");
    }

    @Test
    void inactiveDoctorHasNoSlotsAndThePortAgrees() throws Exception {
        template(MON_9_TO_10);
        Instant nine = Instant.parse("2026-11-16T03:30:00Z");
        LocalDate day = LocalDate.parse(MONDAY);
        assertThat(fx.inOrg(org, () -> port.isAvailable(org, doctor, nine, nine.plusSeconds(900)))).isTrue();
        assertThat(fx.inOrg(org, () -> port.freeSlots(org, doctor, day, day))).hasSize(4);
        fx.inOrg(org, () -> jdbc.sql("UPDATE hms.practitioner SET status = 'INACTIVE' WHERE id = ?").param(doctor)
                .update());
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("date", MONDAY)))
                .andExpect(jsonPath("$.slots", hasSize(0)));
        assertThat(fx.inOrg(org, () -> port.isAvailable(org, doctor, nine, nine.plusSeconds(900)))).isFalse();
    }

    @Test
    void rangeIsCappedAt31Days() throws Exception {
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("from", "2026-11-01").param("to", "2026-12-01")))
                .andExpect(status().isOk());
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("from", "2026-11-01").param("to", "2026-12-02")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(scheduler.apply(get(url + "/slots").param("from", "2026-11-05").param("to", "2026-11-01")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(scheduler.apply(get(url + "/slots"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void authenticationAndPermissions() throws Exception {
        Cookie csrf = fx.csrf();
        mockMvc.perform(get(url + "/slots").param("date", MONDAY)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(url + "/availability").cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(JSON).content("{\"version\":0,\"windows\":[]}")).andExpect(status().isUnauthorized());
        fx.role(org, "PRC_READ", "practitioner:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "PRC_READ");
        mockMvc.perform(reader.apply(get(url + "/availability"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get(url + "/slots").param("date", MONDAY))).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(put(url + "/availability")).contentType(JSON)
                .content("{\"version\":0,\"windows\":[]}")).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(post(url + "/availability-exceptions")).contentType(JSON)
                .content("{\"date\":\"" + MONDAY + "\"}")).andExpect(status().isForbidden());
    }

    @Test
    void anotherOrganizationsDoctorIsNotFound() throws Exception {
        UUID other = fx.organization();
        fx.role(other, "SCHEDULER", "practitioner:read", "practitioner:schedule", "appointment:read");
        AdminApiFixture.Session foreign = fx.loggedIn(other, "scheduler", "SCHEDULER");
        mockMvc.perform(foreign.apply(get(url + "/slots").param("date", MONDAY))).andExpect(status().isNotFound());
        mockMvc.perform(foreign.apply(get(url + "/availability"))).andExpect(status().isNotFound());
        mockMvc.perform(foreign.apply(put(url + "/availability")).contentType(JSON)
                .content("{\"version\":0,\"windows\":[]}")).andExpect(status().isNotFound());
        mockMvc.perform(foreign.apply(post(url + "/availability-exceptions")).contentType(JSON)
                .content("{\"date\":\"" + MONDAY + "\"}")).andExpect(status().isNotFound());
        assertThat(fx.inOrg(other, () -> port.freeSlots(other, doctor, LocalDate.parse(MONDAY),
                LocalDate.parse(MONDAY)))).isEmpty();
    }
}
