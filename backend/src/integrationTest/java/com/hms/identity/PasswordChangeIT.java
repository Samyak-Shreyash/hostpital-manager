package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.support.PostgresIT;
import com.hms.support.TestOrganizations;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** [E1-4] Password policy, change password, forced change on first login. Synthetic data only. */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@Import(PasswordChangeIT.ProbeController.class)
class PasswordChangeIT extends PostgresIT {

    private static final String OLD = "Synthetic-Old-Pass-1";
    private static final String NEW = "Synthetic-New-Pass-2";
    private static final String SESSION_COOKIE = "HMSSESSION";
    private static final String PROBE = "/api/v1/test-password-probe";
    private static final String CHANGE = "/api/v1/auth/change-password";

    @RestController
    static class ProbeController {
        @GetMapping(PROBE)
        @PreAuthorize("isAuthenticated()")
        String probe() {
            return "ok";
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private static String oldHash;
    private UUID userId;
    private String email;

    @BeforeEach
    void createUser() {
        if (oldHash == null) {
            oldHash = passwordEncoder.encode(OLD);
        }
        userId = UUID.randomUUID();
        email = "pw-" + userId + "@demo-clinic.example";
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo User', ?, 'ACTIVE', true, now(), now())
                """)
                .params(userId, TestOrganizations.create(jdbc), email, oldHash)
                .update();
    }

    @Test
    void firstLoginReportsMustChangeAndGatesEverythingButTheAuthEndpoints() throws Exception {
        ResultActions login = login(OLD);
        login.andExpect(jsonPath("$.user.mustChangePassword").value(true));
        Cookie session = sessionOf(login);

        mockMvc.perform(get(PROBE).cookie(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_REQUIRED"))
                .andExpect(jsonPath("$.status").value(403));
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true));
        // Anonymous callers are still 401, not 403.
        mockMvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
    }

    @Test
    void changeClearsTheGateImmediatelyAndNewPasswordLogsIn() throws Exception {
        Cookie session = sessionOf(login(OLD));
        mockMvc.perform(get(PROBE).cookie(session)).andExpect(status().isForbidden());

        change(session, OLD, NEW).andExpect(status().isNoContent());

        mockMvc.perform(get(PROBE).cookie(session)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(jsonPath("$.user.mustChangePassword").value(false));
        login(OLD, status().isUnauthorized());
        login(NEW).andExpect(jsonPath("$.user.mustChangePassword").value(false));
    }

    @Test
    void overlongPasswordAtLoginIsAnOrdinaryWrongPassword() throws Exception {
        login("x".repeat(100), status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(jdbc.sql("SELECT failed_login_count FROM hms.app_user WHERE id = ?").param(userId)
                .query(Integer.class).single()).isEqualTo(1);
        // Unknown email with an over-long password is also 401, not 500
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nobody-" + userId + "@demo-clinic.example\",\"password\":\""
                                + "x".repeat(100) + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overlongCurrentPasswordOnChangeIsAWrongPassword() throws Exception {
        Cookie session = sessionOf(login(OLD));
        change(session, "x".repeat(100), NEW).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));
    }

    @Test
    void logoutIsAllowedWhileChangeIsRequired() throws Exception {
        Cookie session = sessionOf(login(OLD));
        Cookie csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void policyViolationsAreRejectedAndNothingChanges() throws Exception {
        Cookie session = sessionOf(login(OLD));
        String hashBefore = storedHash();

        change(session, OLD, "short").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        change(session, OLD, email).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        change(session, OLD, OLD).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        change(session, OLD, "x".repeat(129)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        change(session, OLD, "x".repeat(73)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));
        // 37 two-byte characters = 74 bytes, 37 characters: the limit is in bytes
        change(session, OLD, "\u00e9".repeat(37)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY_VIOLATION"));

        assertThat(storedHash()).isEqualTo(hashBefore);
        mockMvc.perform(get(PROBE).cookie(session)).andExpect(status().isForbidden());
    }

    @Test
    void wrongCurrentPasswordIsRejectedCountedAndAudited() throws Exception {
        Cookie session = sessionOf(login(OLD));

        change(session, "Wrong-Current-Pass-9", NEW).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INVALID_CURRENT_PASSWORD"));

        assertThat(storedHash()).isEqualTo(oldHash);
        assertThat(jdbc.sql("SELECT failed_login_count FROM hms.app_user WHERE id = ?").param(userId)
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(actions()).contains("PASSWORD_CHANGE_FAILED").doesNotContain("PASSWORD_CHANGED");
    }

    @Test
    void repeatedWrongCurrentPasswordLocksTheAccount() throws Exception {
        Cookie session = sessionOf(login(OLD));
        for (int i = 0; i < 5; i++) {
            change(session, "Wrong-Current-Pass-9", NEW).andExpect(status().isUnprocessableContent());
        }
        change(session, OLD, NEW).andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        assertThat(storedHash()).isEqualTo(oldHash);
    }

    @Test
    void changePasswordNeedsASessionAndTheCsrfToken() throws Exception {
        Cookie session = sessionOf(login(OLD));
        Cookie csrf = csrf();
        String body = json(OLD, NEW);

        mockMvc.perform(post(CHANGE).contentType(MediaType.APPLICATION_JSON).content(body)
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(CHANGE).contentType(MediaType.APPLICATION_JSON).content(body).cookie(session))
                .andExpect(status().isForbidden());
        change(session, "", NEW).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(storedHash()).isEqualTo(oldHash);
    }

    @Test
    void passwordsNeverAppearInLogsOrAuditRows(CapturedOutput output) throws Exception {
        Cookie session = sessionOf(login(OLD));
        change(session, "Wrong-Current-Pass-9", NEW);
        change(session, OLD, "short-pw");
        change(session, OLD, NEW).andExpect(status().isNoContent());

        List<String> audit = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(userId).query(String.class).list();
        assertThat(audit).isNotEmpty();
        String auditText = String.join("\n", audit);
        assertThat(auditText).contains("PASSWORD_CHANGED").contains("passwordHash")
                .doesNotContain(OLD, NEW, "Wrong-Current-Pass-9", "short-pw", oldHash, storedHash());
        assertThat(output.getAll()).doesNotContain(OLD, NEW, "Wrong-Current-Pass-9", "short-pw");
    }

    private ResultActions change(Cookie session, String current, String next) throws Exception {
        Cookie csrf = csrf();
        return mockMvc.perform(post(CHANGE).contentType(MediaType.APPLICATION_JSON).content(json(current, next))
                .cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()));
    }

    private static String json(String current, String next) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}";
    }

    private ResultActions login(String password) throws Exception {
        return login(password, status().isOk());
    }

    private ResultActions login(String password,
            org.springframework.test.web.servlet.ResultMatcher expected) throws Exception {
        Cookie csrf = csrf();
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(expected);
    }

    private Cookie csrf() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static Cookie sessionOf(ResultActions login) {
        String header = login.andReturn().getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "=")).findFirst().orElseThrow();
        String pair = header.split(";", 2)[0];
        return new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1));
    }

    private String storedHash() {
        return jdbc.sql("SELECT password_hash FROM hms.app_user WHERE id = ?").param(userId)
                .query(String.class).single();
    }

    private List<String> actions() {
        return jdbc.sql("SELECT action FROM hms.audit_event WHERE entity_id = ? ORDER BY occurred_at, id")
                .param(userId).query(String.class).list();
    }
}
