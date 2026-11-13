package com.hms.patient.web;

import com.hms.patient.application.PatientService;
import com.hms.patient.application.PatientView;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Patients (api-design.md section 6). Registration and audited detail; search, update and duplicates come later. */
@RestController
@RequestMapping("/api/v1/patients")
class PatientController {

    private final PatientService patients;

    PatientController(PatientService patients) {
        this.patients = patients;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('patient:create')")
    @ResponseStatus(HttpStatus.CREATED)
    PatientView register(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody CreatePatientRequest request) {
        return patients.register(actor, request.toCommand());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('patient:read')")
    PatientView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return patients.get(actor, id);
    }
}
