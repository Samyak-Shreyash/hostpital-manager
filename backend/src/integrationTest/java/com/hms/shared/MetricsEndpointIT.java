package com.hms.shared;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.support.PostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** [E0-7]: Prometheus can scrape the app without credentials; other actuator endpoints stay closed. */
@SpringBootTest
@AutoConfigureMockMvc
class MetricsEndpointIT extends PostgresIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void prometheusEndpointExposesJvmPoolAndHttpMetricsTaggedWithApplication() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")))
                .andExpect(content().string(containsString("hikaricp_connections_active")))
                .andExpect(content().string(containsString("http_server_requests_seconds_bucket")))
                .andExpect(content().string(containsString("application=\"hms\"")));
    }

    @Test
    void otherActuatorEndpointsAreNotExposed() throws Exception {
        mockMvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().is4xxClientError());
    }
}
