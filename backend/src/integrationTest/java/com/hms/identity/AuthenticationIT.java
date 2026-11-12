package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.support.PostgresIT;
import com.hms.support.TestOrganizations;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * [E1-3] Session login against real PostgreSQL and Spring Session JDBC (ADR-0005, api-design.md section 2).
 * Synthetic users only. Each test gets its own user so lockout state never leaks between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthenticationIT extends PostgresIT {

    private static final UUID ORG = UUID.fromString("0190c000-0000-7000-8000-00000000000c");
    private static final String PASSWORD = "Synthetic-Pass-123";
    private static final String SESSION_COOKIE = "HMSSESSION";
    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String passwordHash;
    private UUID userId;
    private String email;

    @BeforeEach
    void createUser() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        TestOrganizations.ensure(jdbc, ORG);
        userId = UUID.randomUUID();
        email = "user-" + userId + "@demo-clinic.example";
        insertUser(userId, email, "ACTIVE");
    }

    @Test
    void loginSucceedsAndTheSessionAuthenticatesLaterRequests() throws Exception {
        Csrf csrf = csrf();
        MvcResult login = mockMvc.perform(login(email, PASSWORD, csrf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()))
                .andExpect(jsonPath("$.user.fullName").value("Demo User"))
                .andExpect(jsonPath("$.organization.id").value(ORG.toString()))
                .andReturn();
        String setCookie = sessionSetCookie(login);
        assertThat(setCookie).contains("HttpOnly").contains("Secure").contains("SameSite=Lax");
        Cookie session = sessionCookie(login);

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(userId.toString()));
        assertThat(auditActions()).containsExactly("LOGIN_SUCCEEDED");
    }

    @Test
    void emailIsMatchedCaseInsensitively() throws Exception {
        mockMvc.perform(login(email.toUpperCase(), PASSWORD, csrf())).andExpect(status().isOk());
    }

    @Test
    void unknownEmailAndWrongPasswordGetTheSameGenericFailure() throws Exception {
        String unknown = mockMvc.perform(login("nobody@demo-clinic.example", PASSWORD, csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String wrong = mockMvc.perform(login(email, "Wrong-Pass-999", csrf()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutRequestId(unknown)).isEqualTo(withoutRequestId(wrong));
    }

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPassword() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(login(email, "Wrong-Pass-999", csrf())).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(email, "Wrong-Pass-999", csrf()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        mockMvc.perform(login(email, PASSWORD, csrf()))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        Instant lockedUntil = jdbc.sql("SELECT locked_until FROM hms.app_user WHERE id = ?")
                .param(userId).query(Instant.class).single();
        assertThat(lockedUntil).isAfter(Instant.now().plusSeconds(14 * 60));
        assertThat(auditActions()).containsExactly(
                "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "LOGIN_FAILED", "ACCOUNT_LOCKED",
                "LOGIN_FAILED");
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(login(email, "Wrong-Pass-999", csrf())).andExpect(status().isUnauthorized());
        }
        mockMvc.perform(login(email, PASSWORD, csrf())).andExpect(status().isOk());
        mockMvc.perform(login(email, "Wrong-Pass-999", csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredLockAllowsLoginAgain() throws Exception {
        jdbc.sql("UPDATE hms.app_user SET failed_login_count = 5, locked_until = now() - interval '1 second' WHERE id = ?")
                .param(userId).update();
        mockMvc.perform(login(email, PASSWORD, csrf())).andExpect(status().isOk());
    }

    @Test
    void inactiveUserWithTheRightPasswordIsRefused() throws Exception {
        jdbc.sql("UPDATE hms.app_user SET status = 'INACTIVE' WHERE id = ?").param(userId).update();
        mockMvc.perform(login(email, PASSWORD, csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_INACTIVE"));
    }

    @Test
    void sessionIdRotatesOnLoginAndTheOldIdStopsWorking() throws Exception {
        Cookie first = mockMvc.perform(login(email, PASSWORD, csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "=")).findFirst().map(AuthenticationIT::toCookie).orElse(null);

        Csrf csrf = csrf();
        Cookie second = mockMvc.perform(login(email, PASSWORD, csrf).cookie(first))
                .andExpect(status().isOk()).andReturn().getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "=")).findFirst().map(AuthenticationIT::toCookie).orElse(null);

        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        mockMvc.perform(get("/api/v1/auth/me").cookie(first)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/auth/me").cookie(second)).andExpect(status().isOk());
    }

    @Test
    void logoutKillsTheSessionServerSide() throws Exception {
        Cookie session = mockMvc.perform(login(email, PASSWORD, csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith(SESSION_COOKIE + "=")).findFirst().map(AuthenticationIT::toCookie).orElse(null);

        Csrf csrf = csrf();
        mockMvc.perform(post("/api/v1/auth/logout").cookie(session, csrf.cookie()).header("X-XSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        Integer sessions = jdbc.sql("SELECT count(*) FROM hms.spring_session WHERE principal_name = ?")
                .param(userId.toString()).query(Integer.class).single();
        assertThat(sessions).isZero();
        assertThat(auditActions()).containsExactly("LOGIN_SUCCEEDED", "LOGOUT");
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, PASSWORD)))
                .andExpect(status().isForbidden());
        assertThat(auditActions()).isEmpty();
    }

    @Test
    void protectedEndpointsNeedASession() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void malformedLoginIsAValidationError() throws Exception {
        mockMvc.perform(login("not-an-email", "", csrf()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void auditMetadataNeverContainsTheEmailOrPassword() throws Exception {
        mockMvc.perform(login(email, "Wrong-Pass-999", csrf()));
        mockMvc.perform(login(email, PASSWORD, csrf()));
        List<String> rows = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(userId).query(String.class).list();
        assertThat(rows).hasSize(2).noneMatch(row -> row.contains(email) || row.contains(PASSWORD)
                || row.contains("Wrong-Pass-999"));
    }

    /** A token pair as an SPA would hold it: the cookie the server issued and the same value as a header. */
    private record Csrf(Cookie cookie, String token) {
    }

    private Csrf csrf() throws Exception {
        Cookie cookie = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie(CSRF_COOKIE);
        assertThat(cookie).as("XSRF-TOKEN cookie issued to anonymous callers").isNotNull();
        return new Csrf(cookie, cookie.getValue());
    }

    /** Spring Session writes its cookie as a raw Set-Cookie header, which MockMvc does not parse into cookies. */
    private static String sessionSetCookie(MvcResult result) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + SESSION_COOKIE + " cookie set; Set-Cookie headers: " + result.getResponse().getHeaders("Set-Cookie") + ", cookies: " + java.util.Arrays.toString(java.util.Arrays.stream(result.getResponse().getCookies()).map(Cookie::getName).toArray())));
    }

    private static Cookie sessionCookie(MvcResult result) {
        return toCookie(sessionSetCookie(result));
    }

    private static Cookie toCookie(String setCookieHeader) {
        String pair = setCookieHeader.split(";", 2)[0];
        return new Cookie(SESSION_COOKIE, pair.substring(pair.indexOf('=') + 1));
    }

    private static MockHttpServletRequestBuilder login(String email, String password, Csrf csrf) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(email, password))
                .cookie(csrf.cookie())
                .header("X-XSRF-TOKEN", csrf.token());
    }

    private static String body(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String withoutRequestId(String json) {
        return json.replaceAll("\"requestId\":\"[^\"]*\"", "");
    }

    private List<String> auditActions() {
        return jdbc.sql("SELECT action FROM hms.audit_event WHERE entity_id = ? ORDER BY occurred_at, id")
                .param(userId).query(String.class).list();
    }

    private void insertUser(UUID id, String email, String status) {
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, status,
                    must_change_password, created_at, updated_at)
                VALUES (?, ?, ?, 'Demo User', ?, ?, false, now(), now())
                """)
                .params(id, ORG, email, passwordHash, status)
                .update();
    }
}
