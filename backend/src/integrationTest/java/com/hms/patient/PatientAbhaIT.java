package com.hms.patient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.crypto.FieldCipher;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * [E3-2] ABHA capture, application-level encryption and exact search (ADR-0009). Synthetic data only: ABHA numbers
 * start with "00" and phones are +91 followed by a number starting with 0, so none can be real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PatientAbhaIT extends PostgresIT {

    private static final String URL = "/api/v1/patients";
    private static final String ABHA = "00123456789012";
    private static final String ABHA_HYPHENATED = "00-1234-5678-9012";

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
    FieldCipher cipher;

    private AdminApiFixture fx;
    private UUID org;
    private AdminApiFixture.Session reception;

    @BeforeEach
    void setUp() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        reception = fx.loggedIn(org, "reception", "RECEPTIONIST");
    }

    private static String body(String name, String abha) {
        return "{\"fullName\":\"" + name + "\",\"sex\":\"F\",\"dateOfBirth\":\"1988-04-12\",\"phone\":\"+910000000001\""
                + (abha == null ? "" : ",\"abhaNumber\":\"" + abha + "\"") + "}";
    }

    private org.springframework.test.web.servlet.ResultActions register(AdminApiFixture.Session s, String json)
            throws Exception {
        return mockMvc.perform(s.apply(post(URL)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String registered(AdminApiFixture.Session s, String name, String abha) throws Exception {
        return register(s, body(name, abha)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
    }

    private List<String> uhids(AdminApiFixture.Session s, String query) throws Exception {
        String json = mockMvc.perform(s.apply(get(URL + query))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return new ArrayList<>(JsonPath.<List<String>>read(json, "$.items[*].uhid"));
    }

    @Test
    void storesOnlyCiphertextHashAndLast4AndReturnsOnlyLast4(CapturedOutput output) throws Exception {
        String created = registered(reception, "Test Abha Holder", ABHA_HYPHENATED);
        assertThat(created).doesNotContain(ABHA).doesNotContain(ABHA_HYPHENATED).doesNotContain("abhaNumber\"");
        assertThat(JsonPath.<String>read(created, "$.abhaLast4")).isEqualTo("9012");
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));

        mockMvc.perform(reception.apply(get(URL + "/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.abhaLast4", is("9012")))
                .andExpect(jsonPath("$.abhaNumber").doesNotExist());

        Map<String, Object> row = fx.inOrg(org, () -> jdbc.sql("""
                SELECT abha_encrypted, abha_key_id, abha_hash, abha_last4 FROM hms.patient WHERE id = ?
                """).param(id).query().singleRow());
        byte[] encrypted = (byte[]) row.get("abha_encrypted");
        assertThat(row.get("abha_key_id")).isEqualTo(cipher.activeKeyId());
        assertThat(row.get("abha_last4")).isEqualTo("9012");
        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain(ABHA);
        assertThat(cipher.decrypt((String) row.get("abha_key_id"), encrypted)).isEqualTo(ABHA);
        assertThat((byte[]) row.get("abha_hash")).hasSize(32);

        String rowText = fx.inOrg(org, () -> jdbc.sql("SELECT row_to_json(p)::text FROM hms.patient p WHERE id = ?")
                .param(id).query(String.class).single());
        assertThat(rowText).doesNotContain(ABHA);
        List<String> audit = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a WHERE entity_id = ?")
                .param(id).query(String.class).list();
        assertThat(audit).isNotEmpty().noneMatch(a -> a.contains(ABHA));
        assertThat(audit).anyMatch(a -> a.contains("\"abhaNumber\""));
        assertThat(output.getAll()).doesNotContain(ABHA).doesNotContain(ABHA_HYPHENATED);
    }

    @Test
    void registrationWithoutAbhaStillWorks() throws Exception {
        register(reception, body("Test No Abha", null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.abhaLast4", nullValue()));
    }

    @Test
    void rejectsMalformedAbhaNumbers() throws Exception {
        for (String bad : List.of("0012345678901", "001234567890123", "00123456789O12", "00 1234 5678 9012",
                "001-234-5678-9012")) {
            register(reception, body("Test Bad Abha", bad)).andExpect(status().isUnprocessableEntity());
        }
        mockMvc.perform(reception.apply(get(URL + "?abha=123"))).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void duplicateAbhaInTheSameOrganizationIsAConflictButAllowedInAnother() throws Exception {
        registered(reception, "Test First Holder", ABHA);
        register(reception, body("Test Second Holder", ABHA_HYPHENATED)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_ABHA")));
        UUID other = fx.organization();
        AdminApiFixture.Session otherReception = fx.loggedIn(other, "reception2", "RECEPTIONIST");
        registered(otherReception, "Test Other Org Holder", ABHA);
    }

    @Test
    void exactAbhaSearchIsOrganizationScoped() throws Exception {
        String mine = JsonPath.read(registered(reception, "Test Abha Searched", ABHA), "$.uhid");
        registered(reception, "Test Other Abha", "00999999999901");
        registered(reception, "Test Without Abha", null);

        assertThat(uhids(reception, "?abha=" + ABHA)).containsExactly(mine);
        assertThat(uhids(reception, "?abha=" + ABHA_HYPHENATED)).containsExactly(mine);
        assertThat(uhids(reception, "?abha=" + ABHA + "&q=Searched")).containsExactly(mine);
        assertThat(uhids(reception, "?abha=" + ABHA + "&q=Without")).isEmpty();
        assertThat(uhids(reception, "?abha=00123456789013")).isEmpty();
        mockMvc.perform(reception.apply(get(URL + "?abha=" + ABHA))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].abhaLast4", is("9012")))
                .andExpect(jsonPath("$.items[0].abhaNumber").doesNotExist());

        UUID other = fx.organization();
        AdminApiFixture.Session otherReception = fx.loggedIn(other, "reception2", "RECEPTIONIST");
        assertThat(uhids(otherReception, "?abha=" + ABHA)).isEmpty();
        String theirs = JsonPath.read(registered(otherReception, "Test Their Holder", ABHA), "$.uhid");
        assertThat(uhids(otherReception, "?abha=" + ABHA)).containsExactly(theirs);
        assertThat(uhids(reception, "?abha=" + ABHA)).containsExactly(mine);

        List<String> audit = jdbc.sql("SELECT row_to_json(a)::text FROM hms.audit_event a "
                + "WHERE action = 'PATIENT_SEARCH' AND actor_id = ?").param(reception.userId())
                .query(String.class).list();
        assertThat(audit).isNotEmpty().noneMatch(a -> a.contains(ABHA));
    }
}
