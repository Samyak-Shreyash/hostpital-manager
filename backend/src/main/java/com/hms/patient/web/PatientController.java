package com.hms.patient.web;

import com.hms.identity.api.Permissions;
import com.hms.patient.application.DuplicateCandidate;
import com.hms.patient.application.PatientService;
import com.hms.patient.application.PatientView;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import com.hms.patient.application.PatientSummary;
import com.hms.shared.PageResult;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Patients (api-design.md section 6): registration with duplicate check, search, audited detail. */
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
            @Valid @RequestBody CreatePatientRequest request, Authentication authentication) {
        boolean mayOverride = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.PATIENT_OVERRIDE_DUPLICATE.equals(a.getAuthority()));
        return patients.register(actor, request.toCommand(), mayOverride);
    }

    /** Pre-registration duplicate lookup (BR-P3); a POST so name, birth date and phone stay out of URLs and logs. */
    @PostMapping("/duplicate-check")
    @PreAuthorize("hasAuthority('patient:create')")
    List<DuplicateCandidate> duplicateCheck(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody DuplicateCheckRequest request) {
        return patients.checkDuplicates(actor, request.fullName(), request.dateOfBirth(), request.phone());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('patient:search')")
    PageResult<PatientSummary> search(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) String q, @RequestParam(required = false) String uhid,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(
                    iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) LocalDate dob,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return patients.search(actor, q, uhid, phone, dob, page, size);
    }

    /** ABHA lookup is a POST so the number stays out of URLs, proxy and access logs. */
    @PostMapping("/search-by-abha")
    @PreAuthorize("hasAuthority('patient:search')")
    PageResult<PatientSummary> searchByAbha(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody SearchByAbhaRequest request, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return patients.searchByAbha(actor, request.abhaNumber(), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('patient:read')")
    PatientView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return patients.get(actor, id);
    }

    /**
     * Partial update ([E3-5]). Name, sex and date of birth also need {@code patient:update-identity} and a reason;
     * accepting suspected duplicates after an identity or phone edit needs {@code patient:override-duplicate}.
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('patient:update')")
    PatientView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdatePatientRequest request, Authentication authentication) {
        return patients.update(actor, id, request.toCommand(),
                has(authentication, Permissions.PATIENT_UPDATE_IDENTITY),
                has(authentication, Permissions.PATIENT_OVERRIDE_DUPLICATE));
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }
}
