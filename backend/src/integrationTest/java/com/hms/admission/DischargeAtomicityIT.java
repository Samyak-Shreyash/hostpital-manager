package com.hms.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.audit.api.AuditRecorder;
import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.IpdFixture;
import com.hms.support.PostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E8-7] A discharge that fails after the bed was released rolls back as a whole (Review Focus 5): the bed stays
 * assigned and the admission stays DISCHARGE_INITIATED. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DischargeAtomicityIT extends PostgresIT {

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

    @MockitoSpyBean
    AuditRecorder audit;

    @Test
    void failureAfterReleaseLeavesBedAssigned() throws Exception {
        AdminApiFixture fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning,
                organizationContext, transactionManager);
        UUID org = fx.organization();
        AdminApiFixture.Session clerk = fx.withAllPermissions(org);
        IpdFixture ipd = new IpdFixture(mockMvc, jdbc, fx, org, clerk);
        UUID bed = ipd.bed(ipd.ward("ATM"), "A-1");
        UUID id = ipd.admitted(clerk, ipd.patient(), ipd.doctor(), bed);
        Instant admitted = Instant.now().minus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), OWNER, OWNER_PASSWORD)) {
            try (PreparedStatement ctx = c.prepareStatement("SELECT set_config('app.organization_id', ?, false)")) {
                ctx.setString(1, org.toString());
                ctx.execute();
            }
            c.createStatement().executeUpdate("UPDATE hms.admission SET admitted_at = '" + admitted
                    + "' WHERE id = '" + id + "'");
            c.createStatement().executeUpdate("UPDATE hms.bed_assignment SET assigned_at = '" + admitted
                    + "' WHERE admission_id = '" + id + "'");
        }
        Instant planned = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        mockMvc.perform(clerk.apply(put("/api/v1/admissions/" + id + "/discharge-summary")).contentType(JSON)
                .content("{\"finalDiagnosis\":\"Synthetic diagnosis\",\"conditionAtDischarge\":\"Stable\","
                        + "\"plannedDischargeAt\":\"" + planned + "\"}")).andExpect(status().isOk());
        long version = fx.inOrg(org, () -> jdbc.sql("SELECT version FROM hms.admission WHERE id = ?").param(id)
                .query(Long.class).single());

        doThrow(new IllegalStateException("injected audit failure")).when(AopTestUtils.<AuditRecorder>getUltimateTargetObject(audit))
                .record(argThat(e -> e != null && "PATIENT_DISCHARGED".equals(e.action())));

        mockMvc.perform(clerk.apply(post("/api/v1/admissions/" + id + "/discharge")).contentType(JSON)
                .content("{\"version\":" + version + ",\"dischargedAt\":\"" + planned
                        + "\",\"deferBilling\":true}")).andExpect(status().is5xxServerError());

        assertThat(ipd.openAssignments(bed)).isEqualTo(1);
        assertThat(fx.inOrg(org, () -> jdbc.sql("SELECT status FROM hms.admission WHERE id = ?").param(id)
                .query(String.class).single())).isEqualTo("DISCHARGE_INITIATED");
        assertThat(fx.auditActions(id)).doesNotContain("BED_RELEASED", "PATIENT_DISCHARGED");
    }
}
