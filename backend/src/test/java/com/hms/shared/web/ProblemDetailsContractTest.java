package com.hms.shared.web;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Error contract (docs/api/api-design.md "Error model"): every error is Problem+JSON with code and requestId. */
class ProblemDetailsContractTest {

    private static final String REQUEST_ID = "test-request-1";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new FailingController())
                .setControllerAdvice(new ProblemDetailsHandler())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    @Test
    void notFoundIsProblemJsonWithCodeTypeAndRequestId() throws Exception {
        perform(get("/fail/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://hms.example/problems/not-found"))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("Patient not found"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(header().string(CorrelationIdFilter.HEADER, REQUEST_ID));
    }

    @Test
    void conflictCarriesItsOwnCode() throws Exception {
        perform(get("/fail/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SLOT_CONFLICT"))
                .andExpect(jsonPath("$.type").value("https://hms.example/problems/slot-conflict"));
    }

    @Test
    void optimisticLockFailureIsConcurrentModification() throws Exception {
        perform(get("/fail/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
    }

    @Test
    void businessRuleIs422() throws Exception {
        perform(get("/fail/rule"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void accessDeniedIs403() throws Exception {
        perform(get("/fail/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void validationErrorListsFieldsWithoutEchoingValues() throws Exception {
        perform(post("/fail/validate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"\",\"dateOfBirth\":\"2999-01-01\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.errors[*].field", hasItem("fullName")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("dateOfBirth")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("NOT_BLANK")))
                .andExpect(jsonPath("$.errors[*].code", hasItem("PAST")))
                .andExpect(content().string(not(containsString("2999-01-01"))));
    }

    @Test
    void malformedBodyIs400MalformedRequest() throws Exception {
        perform(post("/fail/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID));
    }

    @Test
    void unexpectedErrorIs500WithoutLeakingInternals() throws Exception {
        perform(get("/fail/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("db-password-synthetic"))));
    }

    @Test
    void generatesRequestIdWhenMissingOrUnsafe() throws Exception {
        mockMvc.perform(get("/fail/not-found").header(CorrelationIdFilter.HEADER, "bad id\r\nX-Injected: 1"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, not(containsString("bad"))))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.requestId", not(containsString("bad"))));
    }

    private ResultActions perform(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        if (request instanceof org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
            builder.header(CorrelationIdFilter.HEADER, REQUEST_ID);
        }
        return mockMvc.perform(request);
    }

    record PatientForm(@NotBlank String fullName, @Past LocalDate dateOfBirth) {
    }

    @RestController
    static class FailingController {

        @GetMapping("/fail/not-found")
        void notFound() {
            throw new NotFoundException("Patient not found");
        }

        @GetMapping("/fail/conflict")
        void conflict() {
            throw new ConflictException("SLOT_CONFLICT", "The slot is already booked.");
        }

        @GetMapping("/fail/stale")
        void stale() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id-1");
        }

        @GetMapping("/fail/rule")
        void rule() {
            throw new BusinessRuleException("Rule broken.");
        }

        @GetMapping("/fail/denied")
        void denied() {
            throw new AccessDeniedException("no");
        }

        @GetMapping("/fail/boom")
        void boom() {
            throw new IllegalStateException("connection failed for db-password-synthetic");
        }

        @PostMapping("/fail/validate")
        void validate(@Valid @RequestBody PatientForm form) {
        }
    }
}
