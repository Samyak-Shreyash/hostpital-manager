package com.hms.practitioner;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The deferred primary-specialty trigger (V18, SQLSTATE HM002) fails at commit. Application validation normally
 * stops this first, so a test-only endpoint commits a practitioner with no specialty to prove the failure is
 * rendered as 422 PRIMARY_SPECIALTY_REQUIRED and not as a 500. Synthetic data only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PrimarySpecialtyProblemIT.ProbeController.class)
class PrimarySpecialtyProblemIT extends PostgresIT {

    @RestController
    static class ProbeController {

        private final JdbcClient jdbc;
        private final PlatformTransactionManager transactionManager;

        ProbeController(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
            this.jdbc = jdbc;
            this.transactionManager = transactionManager;
        }

        @PostMapping("/api/v1/test-primary-specialty-probe")
        @PreAuthorize("isAuthenticated()")
        void bare(@RequestParam UUID org, @RequestParam UUID user) {
            // The commit at the end of the template raises the deferred trigger failure.
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.sql("""
                    INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no, created_by,
                        created_at, updated_at) VALUES (?, ?, ?, 'Dr. Test Bare', 'TEST-2001', ?, now(), now())""")
                    .params(UUID.randomUUID(), org, user, user).update());
        }
    }

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

    @Test
    void aPractitionerCommittedWithoutAPrimarySpecialtyIs422() throws Exception {
        AdminApiFixture fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning,
                organizationContext, transactionManager);
        UUID org = fx.organization();
        AdminApiFixture.Session admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        UUID user = fx.user(org, fx.uniqueEmail("doctor"), "DOCTOR");

        mockMvc.perform(admin.apply(post("/api/v1/test-primary-specialty-probe")
                        .param("org", org.toString()).param("user", user.toString())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("PRIMARY_SPECIALTY_REQUIRED")))
                .andExpect(jsonPath("$.requestId", notNullValue()));
    }
}
