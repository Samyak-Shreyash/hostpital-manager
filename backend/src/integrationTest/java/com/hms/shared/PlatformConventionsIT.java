package com.hms.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.shared.domain.Money;
import com.hms.shared.web.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/** [E0-5] conventions wired into the real application context. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class PlatformConventionsIT {

    private static final Logger log = LoggerFactory.getLogger(PlatformConventionsIT.class);

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void unauthenticatedRequestGetsProblemJson401WithEchoedRequestId() throws Exception {
        mockMvc.perform(get("/api/v1/patients").header(CorrelationIdFilter.HEADER, "it-request-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.requestId").value("it-request-1"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "it-request-1"));
    }

    @Test
    void healthStaysPublicAndGetsARequestId() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().exists(CorrelationIdFilter.HEADER));
    }

    @Test
    void logsAreJsonWithModuleAndCorrelationIdAndRedactPhi(CapturedOutput output) {
        MDC.put(CorrelationIdFilter.MDC_KEY, "it-log-1");
        try {
            log.atInfo()
                    .addKeyValue("password", "synthetic-secret-1")
                    .log("Callback requested by patient.two@example.test on 9876501234");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        String line = output.getOut().lines()
                .filter(l -> l.contains("Callback requested by"))
                .findFirst()
                .orElseThrow();
        assertThat(line).startsWith("{")
                .contains("\"module\":\"shared\"", "\"correlationId\":\"it-log-1\"", "[EMAIL]", "[PHONE]",
                        "\"password\":\"[REDACTED]\"")
                .doesNotContain("patient.two@example.test", "9876501234", "synthetic-secret-1");
    }

    @Test
    void applicationJsonMapperWritesMoneyAsString() {
        assertThat(jsonMapper.writeValueAsString(Money.of("10.5"))).isEqualTo("\"10.50\"");
    }
}
