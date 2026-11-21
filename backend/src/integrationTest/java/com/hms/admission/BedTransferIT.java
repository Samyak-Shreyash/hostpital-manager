package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

/** [E8-4] Bed transfer: history, validation, lock ordering under contention. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
class BedTransferIT extends PostgresIT {

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
    private AdminApiFixture.Session clerk;
    private IpdFixture ipd;
    private UUID ward;
    private UUID doctor;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("XFER");
        doctor = ipd.doctor();
    }

    private ResultActions transfer(AdminApiFixture.Session who, UUID admission, UUID toBed) throws Exception {
        return mockMvc.perform(who.apply(post("/api/v1/admissions/" + admission + "/bed-assignments/transfer"))
                .contentType(JSON).content("{\"toBedId\":\"" + toBed + "\",\"reason\":\"Synthetic reason\"}"));
    }

    @Test
    void transferPreservesHistory() throws Exception {
        UUID a = ipd.bed(ward, "T-1");
        UUID b = ipd.bed(ward, "T-2");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);

        transfer(clerk, id, b).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentAssignment.bedId", is(b.toString())))
                .andExpect(jsonPath("$.currentAssignment.releasedAt", nullValue()))
                .andExpect(jsonPath("$.currentAssignment.reason", is("Synthetic reason")))
                .andExpect(jsonPath("$.previousAssignment.bedId", is(a.toString())))
                .andExpect(jsonPath("$.previousAssignment.releasedAt", notNullValue()));

        String history = mockMvc.perform(clerk.apply(get("/api/v1/admissions/" + id + "/bed-assignments")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2))).andReturn().getResponse()
                .getContentAsString();
        String released = JsonPath.read(history, "$[0].releasedAt");
        String assigned = JsonPath.read(history, "$[1].assignedAt");
        assertThat(released).isEqualTo(assigned);
        assertThat(ipd.openAssignments(a)).isZero();
        assertThat(ipd.openAssignments(b)).isEqualTo(1);
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE action = 'BED_TRANSFERRED'"
                + " AND entity_id = ?").param(id).query(Long.class).single())).isEqualTo(1);
    }

    @Test
    void transferToTheSameBedIs422() throws Exception {
        UUID a = ipd.bed(ward, "S-1");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        transfer(clerk, id, a).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/bed-assignments/transfer"))
                .contentType(JSON).content("{\"toBedId\":\"" + ipd.bed(ward, "S-2") + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transferToOutOfServiceBedIs409() throws Exception {
        UUID a = ipd.bed(ward, "O-1");
        UUID b = ipd.bed(ward, "O-2");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        mockMvc.perform(clerk.apply(patch("/api/v1/beds/" + b)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}")).andExpect(status().isOk());
        transfer(clerk, id, b).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("BED_NOT_AVAILABLE")));
        assertThat(ipd.openAssignments(a)).isEqualTo(1);
    }

    @Test
    void transferOfACancelledAdmissionIs409InvalidState() throws Exception {
        UUID a = ipd.bed(ward, "C-1");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/cancel")).contentType(JSON)
                .content("{\"version\":0}")).andExpect(status().isOk());
        transfer(clerk, id, ipd.bed(ward, "C-2")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("INVALID_STATE_TRANSITION")));
    }

    @Test
    void missingBedAssignIs403() throws Exception {
        UUID a = ipd.bed(ward, "F-1");
        UUID b = ipd.bed(ward, "F-2");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        fx.role(org, "READER", "admission:read", "bed:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READER");
        transfer(reader, id, b).andExpect(status().isForbidden());
    }

    @Test
    void otherOrgAdmissionOrBedIs404() throws Exception {
        UUID a = ipd.bed(ward, "N-1");
        UUID id = ipd.admitted(clerk, ipd.patient(), doctor, a);
        UUID otherOrg = fx.organization();
        AdminApiFixture.Session other = fx.withAllPermissions(otherOrg);
        IpdFixture otherIpd = new IpdFixture(mockMvc, jdbc, fx, otherOrg, other);
        UUID foreignBed = otherIpd.bed(otherIpd.ward("OTHER"), "Z-1");
        transfer(other, id, foreignBed).andExpect(status().isNotFound());
        transfer(clerk, id, foreignBed).andExpect(status().isNotFound());
        assertThat(ipd.openAssignments(a)).isEqualTo(1);
    }

    @Test
    void crossTransfersDoNotDeadlock() {
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            UUID a = ipd.bed(ward, "X-A");
            UUID b = ipd.bed(ward, "X-B");
            UUID c = ipd.bed(ward, "X-C");
            UUID d = ipd.bed(ward, "X-D");
            UUID x = ipd.admitted(clerk, ipd.patient(), doctor, a);
            UUID y = ipd.admitted(clerk, ipd.patient(), doctor, b);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 50; round++) {
                    List<MockHttpServletResponse> r = race(pool, () -> send(x, b), () -> send(y, a));
                    for (MockHttpServletResponse res : r) {
                        assertThat(res.getStatus()).isEqualTo(409);
                        assertThat((Object) JsonPath.read(res.getContentAsString(), "$.code"))
                                .isEqualTo("BED_NOT_AVAILABLE");
                    }
                    assertInvariants();
                    assertThat(ipd.openAssignments(a)).isEqualTo(1);
                    assertThat(ipd.openAssignments(b)).isEqualTo(1);

                    r = race(pool, () -> send(x, c), () -> send(y, c));
                    assertThat(r.stream().filter(s -> s.getStatus() == 200).count()).isEqualTo(1);
                    assertThat(r.stream().filter(s -> s.getStatus() == 409).count()).isEqualTo(1);
                    assertInvariants();
                    if (r.get(0).getStatus() == 200) {
                        transfer(clerk, x, a).andExpect(status().isOk());
                    } else {
                        transfer(clerk, y, b).andExpect(status().isOk());
                    }

                    String body = IpdFixture.admissionBody(ipd.patient(), doctor, d, null);
                    r = race(pool, () -> send(x, d), () -> mockMvc.perform(clerk.apply(post("/api/v1/admissions"))
                            .contentType(JSON).content(body)).andReturn().getResponse());
                    boolean moved = r.get(0).getStatus() == 200;
                    boolean admitted = r.get(1).getStatus() == 201;
                    assertThat(moved ^ admitted).isTrue();
                    assertInvariants();
                    if (moved) {
                        transfer(clerk, x, a).andExpect(status().isOk());
                    } else {
                        String newId = JsonPath.read(r.get(1).getContentAsString(), "$.id");
                        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + newId + "/cancel"))
                                .contentType(JSON).content("{\"version\":0}")).andExpect(status().isOk());
                    }
                    assertInvariants();
                }
            } finally {
                pool.shutdownNow();
            }
        });
    }

    private MockHttpServletResponse send(UUID admission, UUID toBed) throws Exception {
        return transfer(clerk, admission, toBed).andReturn().getResponse();
    }

    @SafeVarargs
    private List<MockHttpServletResponse> race(ExecutorService pool, Callable<MockHttpServletResponse>... calls)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(calls.length);
        List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
        for (Callable<MockHttpServletResponse> call : calls) {
            futures.add(pool.submit(() -> {
                barrier.await();
                return call.call();
            }));
        }
        List<MockHttpServletResponse> out = new ArrayList<>();
        for (Future<MockHttpServletResponse> f : futures) {
            MockHttpServletResponse res = f.get();
            assertThat(res.getStatus()).isLessThan(500);
            assertThat(res.getContentAsString()).doesNotContain("40P01");
            out.add(res);
        }
        return out;
    }

    /** I1: at most one open assignment per bed; I2: every active admission holds exactly one bed. */
    private void assertInvariants() {
        fx.inOrg(org, () -> {
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM (SELECT bed_id FROM hms.bed_assignment WHERE released_at IS NULL
                    GROUP BY bed_id HAVING count(*) > 1) t""").query(Long.class).single()).isZero();
            assertThat(jdbc.sql("""
                    SELECT count(*) FROM hms.admission ad WHERE ad.status IN ('ADMITTED', 'DISCHARGE_INITIATED')
                    AND (SELECT count(*) FROM hms.bed_assignment ba WHERE ba.admission_id = ad.id
                         AND ba.released_at IS NULL) <> 1""").query(Long.class).single()).isZero();
            return null;
        });
    }
}
