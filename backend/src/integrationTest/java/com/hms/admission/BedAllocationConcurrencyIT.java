package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
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
 * [E8-3] I1 and I3 under contention: parallel admissions racing for one bed, or of one patient, leave exactly one
 * winner and answer every loser with its 409 code, never a 500. Harness copied from {@code AppointmentBookingIT}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BedAllocationConcurrencyIT extends PostgresIT {

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

    private AdminApiFixture.Session clerk;
    private IpdFixture ipd;
    private UUID ward;
    private UUID doctor;

    @BeforeEach
    void setUp() throws Exception {
        AdminApiFixture fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning,
                organizationContext, transactionManager);
        UUID org = fx.organization();
        clerk = fx.withAllPermissions(org);
        ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        ward = ipd.ward("RACE");
        doctor = ipd.doctor();
    }

    @Test
    void hundredParallelAdmissionsToOneBed() throws Exception {
        UUID bed = ipd.bed(ward, "R-1");
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            bodies.add(IpdFixture.admissionBody(ipd.patient(), doctor, bed, null));
        }
        List<int[]> outcome = race(bodies, "BED_NOT_AVAILABLE");
        assertThat(outcome.stream().filter(o -> o[0] == 201).count()).isEqualTo(1);
        assertThat(outcome.stream().filter(o -> o[0] == 409).count()).isEqualTo(99);
        assertThat(ipd.openAssignments(bed)).isEqualTo(1);
    }

    @Test
    void twentyParallelAdmissionsOfOnePatient() throws Exception {
        UUID patient = ipd.patient();
        List<String> bodies = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bodies.add(IpdFixture.admissionBody(patient, doctor, ipd.bed(ward, "P-" + i), null));
        }
        List<int[]> outcome = race(bodies, "PATIENT_ALREADY_ADMITTED");
        assertThat(outcome.stream().filter(o -> o[0] == 201).count()).isEqualTo(1);
        assertThat(outcome.stream().filter(o -> o[0] == 409).count()).isEqualTo(19);
    }

    /** Fires every body at once; each 409 must carry {@code conflictCode}. */
    private List<int[]> race(List<String> bodies, String conflictCode) throws Exception {
        int threads = bodies.size();
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<int[]> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (String body : bodies) {
                results.add(pool.submit(() -> {
                    barrier.await();
                    var response = mockMvc.perform(clerk.apply(post("/api/v1/admissions")).contentType(JSON)
                            .content(body)).andReturn().getResponse();
                    if (response.getStatus() == 409) {
                        assertThat((Object) JsonPath.read(response.getContentAsString(), "$.code"))
                                .isEqualTo(conflictCode);
                    }
                    return response.getStatus();
                }));
            }
            for (Future<Integer> f : results) {
                statuses.add(new int[] {f.get()});
            }
        } finally {
            pool.shutdownNow();
        }
        return statuses;
    }
}
