package com.hms.bedmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.identity.AdminApiFixture;
import com.hms.identity.api.RoleProvisioning;
import com.hms.shared.tenancy.OrganizationContext;
import com.hms.support.PostgresIT;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.PlatformTransactionManager;

/** [E8-1] Wards, beds and availability. Synthetic data only; negative tests use a valid session and CSRF token. */
@SpringBootTest
@AutoConfigureMockMvc
class WardBedIT extends PostgresIT {

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
    private AdminApiFixture.Session admin;
    private UUID rateService;

    @BeforeEach
    void organizationWithAFacilityAdministrator() throws Exception {
        fx = new AdminApiFixture(mockMvc, jdbc, passwordEncoder, roleProvisioning, organizationContext,
                transactionManager);
        org = fx.organization();
        admin = fx.loggedIn(org, "facility", "FACILITY_ADMIN");
        rateService = newService(admin, "ROOM_GEN");
    }

    private UUID newService(AdminApiFixture.Session session, String code) throws Exception {
        String body = mockMvc.perform(session.apply(post("/api/v1/services")).contentType(JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Room " + code + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private UUID newWard(AdminApiFixture.Session session, String code) throws Exception {
        String body = mockMvc.perform(session.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"Ward " + code + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(0)))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    private String bedBody(String bedNo, UUID service) {
        return "{\"bedNo\":\"" + bedNo + "\",\"dailyRateServiceId\":\"" + service + "\"}";
    }

    private UUID newBed(AdminApiFixture.Session session, UUID ward, String bedNo) throws Exception {
        String body = mockMvc.perform(session.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content(bedBody(bedNo, rateService)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.wardId", is(ward.toString())))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    @Test
    void createWardAndManyBeds() throws Exception {
        UUID ward = newWard(admin, "GENWARD");
        for (int i = 1; i <= 120; i++) {
            newBed(admin, ward, "G-" + i);
        }
        mockMvc.perform(admin.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wards", hasSize(1)))
                .andExpect(jsonPath("$.wards[0].ward.id", is(ward.toString())))
                .andExpect(jsonPath("$.wards[0].total", is(120)))
                .andExpect(jsonPath("$.wards[0].available", is(120)))
                .andExpect(jsonPath("$.wards[0].occupied", is(0)))
                .andExpect(jsonPath("$.wards[0].outOfService", is(0)))
                .andExpect(jsonPath("$.wards[0].beds", hasSize(120)))
                .andExpect(jsonPath("$.wards[0].beds[0].occupied", is(false)))
                .andExpect(jsonPath("$.wards[0].beds[0].admissionId", nullValue()))
                .andExpect(jsonPath("$.wards[0].beds[0].ward.name", is("Ward GENWARD")));
        mockMvc.perform(admin.apply(get("/api/v1/wards/" + ward + "/beds")).param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(100)))
                .andExpect(jsonPath("$.totalItems", is(120)));
    }

    @Test
    void wardCareTypeDefaultsToGeneralAndIsEditable() throws Exception {
        UUID ward = newWard(admin, "CTW");
        mockMvc.perform(admin.apply(get("/api/v1/wards")).param("size", "100")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id=='" + ward + "')].careType", contains("GENERAL")));
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                        .content("{\"version\":0,\"careType\":\"CRITICAL_CARE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.careType", is("CRITICAL_CARE")))
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                        .content("{\"version\":1,\"careType\":\"VIP\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(jsonPath("$.wards[0].ward.careType", is("CRITICAL_CARE")));
        mockMvc.perform(admin.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"ICU2\",\"name\":\"Intensive Care\",\"careType\":\"CRITICAL_CARE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.careType", is("CRITICAL_CARE")));
    }

    @Test
    void outOfServiceBedIsNotAvailable() throws Exception {
        UUID ward = newWard(admin, "ICU");
        UUID bed = newBed(admin, ward, "I-1");
        newBed(admin, ward, "I-2");
        mockMvc.perform(admin.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                        .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OUT_OF_SERVICE")))
                .andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(admin.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(jsonPath("$.wards[0].total", is(2)))
                .andExpect(jsonPath("$.wards[0].available", is(1)))
                .andExpect(jsonPath("$.wards[0].outOfService", is(1)))
                .andExpect(jsonPath("$.wards[0].beds[?(@.bedNo=='I-1')].status", contains("OUT_OF_SERVICE")));
    }

    @Test
    void duplicateBedNoInWardIs409() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID other = newWard(admin, "W2");
        newBed(admin, ward, "B-1");
        mockMvc.perform(admin.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content(bedBody("B-1", rateService)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_BED_NO")));
        newBed(admin, other, "B-1"); // same number in another ward is fine
        mockMvc.perform(admin.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"W1\",\"name\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("DUPLICATE_WARD_CODE")));
    }

    @Test
    void unknownOrOtherOrgDailyRateServiceIs422() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID bed = newBed(admin, ward, "B-1");
        UUID otherOrg = fx.organization();
        AdminApiFixture.Session otherAdmin = fx.loggedIn(otherOrg, "other", "FACILITY_ADMIN");
        UUID foreign = newService(otherAdmin, "ROOM_FOREIGN");
        for (UUID bad : new UUID[] {UUID.randomUUID(), foreign}) {
            mockMvc.perform(admin.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                            .content(bedBody("B-2", bad)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
            mockMvc.perform(admin.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                            .content("{\"version\":0,\"dailyRateServiceId\":\"" + bad + "\"}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        }
        mockMvc.perform(admin.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"W9\",\"name\":\"X\",\"departmentId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("VALIDATION_ERROR")));
        mockMvc.perform(admin.apply(get("/api/v1/wards/" + ward + "/beds")))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].dailyRateServiceId", is(rateService.toString())))
                .andExpect(jsonPath("$.items[0].version", is(0)));
    }

    @Test
    void staleVersionIs409() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID bed = newBed(admin, ward, "B-1");
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                        .content("{\"version\":0,\"name\":\"Renamed\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version", is(1)));
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                        .content("{\"version\":0,\"name\":\"Again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
        mockMvc.perform(admin.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                        .content("{\"version\":0,\"bedClass\":\"PRIVATE\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.bedClass", is("PRIVATE")));
        mockMvc.perform(admin.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                        .content("{\"version\":0,\"bedClass\":\"GENERAL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("CONCURRENT_MODIFICATION")));
    }

    @Test
    void missingBedManageIs403() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID bed = newBed(admin, ward, "B-1");
        fx.role(org, "READERS", "ward:read", "bed:read");
        fx.role(org, "NOTHING", "org:read");
        fx.role(org, "BED_ONLY", "bed:read");
        AdminApiFixture.Session reader = fx.loggedIn(org, "reader", "READERS");
        AdminApiFixture.Session nobody = fx.loggedIn(org, "nobody", "NOTHING");
        AdminApiFixture.Session bedOnly = fx.loggedIn(org, "bedonly", "BED_ONLY");

        mockMvc.perform(reader.apply(get("/api/v1/wards"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get("/api/v1/wards/" + ward + "/beds"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(get("/api/v1/beds/availability"))).andExpect(status().isOk());
        mockMvc.perform(reader.apply(post("/api/v1/wards")).contentType(JSON)
                .content("{\"code\":\"NEW1\",\"name\":\"New\"}")).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                .content("{\"version\":0,\"name\":\"X\"}")).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                .content(bedBody("B-2", rateService))).andExpect(status().isForbidden());
        mockMvc.perform(reader.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}")).andExpect(status().isForbidden());
        mockMvc.perform(nobody.apply(get("/api/v1/wards"))).andExpect(status().isForbidden());
        mockMvc.perform(nobody.apply(get("/api/v1/wards/" + ward + "/beds"))).andExpect(status().isForbidden());
        mockMvc.perform(nobody.apply(get("/api/v1/beds/availability"))).andExpect(status().isForbidden());
        mockMvc.perform(bedOnly.apply(get("/api/v1/wards"))).andExpect(status().isForbidden());
        mockMvc.perform(bedOnly.apply(get("/api/v1/beds/availability"))).andExpect(status().isOk());
    }

    @Test
    void otherOrgWardAndBedAre404() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID bed = newBed(admin, ward, "B-1");
        UUID otherOrg = fx.organization();
        AdminApiFixture.Session other = fx.loggedIn(otherOrg, "other", "FACILITY_ADMIN");
        mockMvc.perform(other.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                .content("{\"version\":0,\"name\":\"X\"}")).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(get("/api/v1/wards/" + ward + "/beds"))).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                .content(bedBody("B-9", rateService))).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                .content("{\"version\":0,\"status\":\"OUT_OF_SERVICE\"}")).andExpect(status().isNotFound());
        mockMvc.perform(other.apply(get("/api/v1/beds/availability")).param("wardId", ward.toString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(other.apply(get("/api/v1/wards"))).andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(other.apply(get("/api/v1/beds/availability"))).andExpect(jsonPath("$.wards", hasSize(0)));
    }

    @Test
    void writesAreAudited() throws Exception {
        UUID ward = newWard(admin, "W1");
        UUID bed = newBed(admin, ward, "B-1");
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                .content("{\"version\":0,\"name\":\"Renamed\"}")).andExpect(status().isOk());
        mockMvc.perform(admin.apply(patch("/api/v1/beds/" + bed)).contentType(JSON)
                .content("{\"version\":0,\"bedClass\":\"PRIVATE\"}")).andExpect(status().isOk());
        assertThat(fx.auditActions(ward)).containsExactlyInAnyOrder("WARD_CREATED", "WARD_UPDATED");
        assertThat(fx.auditActions(bed)).containsExactlyInAnyOrder("BED_CREATED", "BED_UPDATED");
    }

    @Test
    void validationRejectsBadInput() throws Exception {
        UUID ward = newWard(admin, "W1");
        mockMvc.perform(admin.apply(post("/api/v1/wards")).contentType(JSON)
                        .content("{\"code\":\"bad code\",\"name\":\"X\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin.apply(post("/api/v1/wards/" + ward + "/beds")).contentType(JSON)
                        .content("{\"bedNo\":\"\",\"dailyRateServiceId\":\"" + rateService + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON)
                        .content("{\"version\":0,\"status\":\"BOGUS\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(admin.apply(patch("/api/v1/wards/" + ward)).contentType(JSON).content("{\"version\":0}"))
                .andExpect(status().isUnprocessableEntity());
    }
}
