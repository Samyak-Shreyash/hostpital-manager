package com.hms.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.domain.PasswordPolicy;
import com.hms.shared.tenancy.OrganizationContext;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * [E2-1] First-run bootstrap. Uses its own database because the shared test database already holds organizations
 * (the bootstrap only runs on an empty installation). The bootstrap runs during context startup, from properties.
 * Synthetic data only.
 */
@SpringBootTest(properties = {
        "hms.bootstrap.org-name=Demo Bootstrap Clinic",
        "hms.bootstrap.admin-email=first.admin@demo-clinic.example",
        "hms.bootstrap.org-timezone=Asia/Kolkata"})
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class BootstrapIT {

    private static final String ADMIN_EMAIL = "first.admin@demo-clinic.example";

    private static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine").withInitScript("db/init-roles.sql");

    private static final Path PASSWORD_FILE;

    static {
        postgres.start();
        try {
            PASSWORD_FILE = Files.createTempDirectory("hms-bootstrap-it").resolve("admin-password.txt");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "hms_app");
        registry.add("spring.datasource.password", () -> "app-test-only");
        registry.add("spring.flyway.user", () -> "hms_owner");
        registry.add("spring.flyway.password", () -> "owner-test-only");
        registry.add("hms.bootstrap.admin-password-file", PASSWORD_FILE::toString);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    FirstOrganizationBootstrap bootstrap;

    @Autowired
    OrganizationContext organizationContext;

    @Autowired
    PlatformTransactionManager transactionManager;

    private String password() throws IOException {
        return Files.readString(PASSWORD_FILE).trim();
    }

    @Test
    void createsOrganizationRolesAndAdministratorWithTheTemporaryPasswordOnlyInTheFile(CapturedOutput output)
            throws Exception {
        assertThat(jdbc.sql("SELECT count(*) FROM hms.organization").query(Integer.class).single()).isEqualTo(1);
        UUID org = jdbc.sql("SELECT id FROM hms.organization").query(UUID.class).single();
        assertThat(jdbc.sql("SELECT name FROM hms.organization").query(String.class).single())
                .isEqualTo("Demo Bootstrap Clinic");
        assertThat(jdbc.sql("SELECT timezone FROM hms.organization").query(String.class).single())
                .isEqualTo("Asia/Kolkata");
        // role is under RLS: read it in the organization's context
        int roles = organizationContext.runAs(org, () -> new TransactionTemplate(transactionManager).execute(
                tx -> jdbc.sql("SELECT count(*) FROM hms.role").query(Integer.class).single()));
        assertThat(roles).isEqualTo(6);

        String password = password();
        assertThat(password).hasSize(16);
        PasswordPolicy.require(password, ADMIN_EMAIL);

        // The password is in the file only: not in the logs, not in any audit row.
        assertThat(output.getAll()).doesNotContain(password);
        String audit = jdbc.sql("SELECT coalesce(string_agg(a::text, '|'), '') FROM hms.audit_event a")
                .query(String.class).single();
        assertThat(audit).doesNotContain(password).contains("ORGANIZATION_CREATED").contains("USER_CREATED");
        String stored = jdbc.sql("SELECT password_hash FROM hms.app_user WHERE email = ?").param(ADMIN_EMAIL)
                .query(String.class).single();
        assertThat(stored).isNotEqualTo(password).startsWith("$2");

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(PASSWORD_FILE)))
                    .isEqualTo("rw-------");
        }
    }

    @Test
    void theAdministratorCanLogInAndMustChangeThePassword() throws Exception {
        Cookie csrf = mockMvc.perform(get("/api/v1/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + password() + "\"}")
                        .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword", is(true)))
                .andExpect(jsonPath("$.user.roles[0]", is("SYSTEM_ADMIN")))
                .andExpect(jsonPath("$.organization.name", is("Demo Bootstrap Clinic")))
                .andExpect(jsonPath("$.organization.timezone", is("Asia/Kolkata")))
                .andReturn();
        String pair = login.getResponse().getHeaders("Set-Cookie").stream()
                .filter(h -> h.startsWith("HMSSESSION=")).findFirst().orElseThrow().split(";", 2)[0];
        Cookie session = new Cookie("HMSSESSION", pair.substring(pair.indexOf('=') + 1));

        mockMvc.perform(get("/api/v1/organization").cookie(session, csrf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PASSWORD_CHANGE_REQUIRED")));
    }

    @Test
    void runningAgainIsANoOpAndKeepsThePasswordFile() throws Exception {
        String before = Files.readString(PASSWORD_FILE);
        int orgs = jdbc.sql("SELECT count(*) FROM hms.organization").query(Integer.class).single();

        assertThat(bootstrap.organizationExists()).isTrue();
        assertThat(bootstrap.bootstrap("Another Clinic", "Asia/Kolkata", "second.admin@demo-clinic.example",
                "Synthetic-Pass-123")).isEmpty();
        new BootstrapRunner(bootstrap, "Demo Bootstrap Clinic", ADMIN_EMAIL, "", PASSWORD_FILE.toString())
                .run(null);

        assertThat(jdbc.sql("SELECT count(*) FROM hms.organization").query(Integer.class).single()).isEqualTo(orgs);
        assertThat(jdbc.sql("SELECT count(*) FROM hms.app_user").query(Integer.class).single()).isEqualTo(1);
        assertThat(Files.readString(PASSWORD_FILE)).isEqualTo(before);
    }

    @Test
    void restartWithTheVariablesButWithoutThePasswordFileStartsNormally() {
        // An organization exists: the variables are left set, the password file was deleted after first login.
        new BootstrapRunner(bootstrap, "Demo Bootstrap Clinic", ADMIN_EMAIL, "", "").run(null);
        new BootstrapRunner(bootstrap, "", ADMIN_EMAIL, "", "").run(null);
        assertThat(jdbc.sql("SELECT count(*) FROM hms.app_user").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void variablesWithoutAPasswordFileFailWithAClearMessage() {
        // Validation only matters on a fresh installation, so this runs against one with no organization.
        FirstOrganizationBootstrap bootstrap = org.mockito.Mockito.mock(FirstOrganizationBootstrap.class);
        BootstrapRunner runner = new BootstrapRunner(bootstrap, "Demo Bootstrap Clinic", ADMIN_EMAIL, "", "");
        assertThatThrownBy(() -> runner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE");
        assertThatThrownBy(() -> new BootstrapRunner(bootstrap, "", ADMIN_EMAIL, "", "x").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HMS_BOOTSTRAP_ORG_NAME");
        assertThatThrownBy(() -> new BootstrapRunner(bootstrap, "Demo", ADMIN_EMAIL, "Mars/Base", "x").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IANA");
    }

    @Test
    void unconfiguredBootstrapDoesNothing() {
        new BootstrapRunner(bootstrap, "", "", "", "").run(null);
        assertThat(List.of(PASSWORD_FILE)).allMatch(Files::exists);
    }
}
