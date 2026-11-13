package com.hms.practitioner.web;

import com.hms.practitioner.application.PractitionerCommands;
import com.hms.practitioner.application.PractitionerService;
import com.hms.practitioner.application.PractitionerView;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Doctors ([E4-1], api-design.md section 5): registration, profile, specialties and departments. */
@RestController
@RequestMapping("/api/v1/doctors")
class PractitionerController {

    private final PractitionerService practitioners;

    PractitionerController(PractitionerService practitioners) {
        this.practitioners = practitioners;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('practitioner:create')")
    @ResponseStatus(HttpStatus.CREATED)
    PractitionerView register(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody PractitionerRequests.Create request) {
        return practitioners.register(actor, request.toCommand());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('practitioner:read')")
    PageResult<PractitionerView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) UUID specialtyId, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return practitioners.list(actor, specialtyId, departmentId, q, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('practitioner:read')")
    PractitionerView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return practitioners.get(actor, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('practitioner:update')")
    PractitionerView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody PractitionerRequests.Update request) {
        return practitioners.update(actor, id, request.toCommand());
    }

    @PutMapping("/{id}/specialties")
    @PreAuthorize("hasAuthority('practitioner:update')")
    PractitionerView replaceSpecialties(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody PractitionerRequests.Specialties request) {
        return practitioners.replaceSpecialties(actor, id, new PractitionerCommands.ReplaceSpecialties(
                request.version(), request.specialtyIds(), request.primarySpecialtyId()));
    }

    @PutMapping("/{id}/departments")
    @PreAuthorize("hasAuthority('practitioner:update')")
    PractitionerView replaceDepartments(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody PractitionerRequests.Departments request) {
        return practitioners.replaceDepartments(actor, id, new PractitionerCommands.ReplaceDepartments(
                request.version(), request.departmentIds()));
    }
}
