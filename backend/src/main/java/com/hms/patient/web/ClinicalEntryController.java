package com.hms.patient.web;

import com.hms.patient.application.ClinicalEntryService;
import com.hms.patient.application.ClinicalEntryViews.AllergyView;
import com.hms.patient.application.ClinicalEntryViews.HistoryView;
import com.hms.patient.web.ClinicalEntryRequests.CreateAllergyRequest;
import com.hms.patient.web.ClinicalEntryRequests.CreateHistoryRequest;
import com.hms.patient.web.ClinicalEntryRequests.EntryStatusRequest;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Allergies and medical history of a patient ([E3-4], api-design.md section 6). */
@RestController
@RequestMapping("/api/v1/patients/{patientId}")
class ClinicalEntryController {

    private final ClinicalEntryService service;

    ClinicalEntryController(ClinicalEntryService service) {
        this.service = service;
    }

    @GetMapping("/allergies")
    @PreAuthorize("hasAuthority('patient:read')")
    List<AllergyView> allergies(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId) {
        return service.listAllergies(actor, patientId);
    }

    @PostMapping("/allergies")
    @PreAuthorize("hasAuthority('patient:update-clinical')")
    @ResponseStatus(HttpStatus.CREATED)
    AllergyView addAllergy(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId,
            @Valid @RequestBody CreateAllergyRequest request) {
        return service.addAllergy(actor, patientId, request.substance(), request.reaction(), request.severity());
    }

    @PatchMapping("/allergies/{entryId}")
    @PreAuthorize("hasAuthority('patient:update-clinical')")
    AllergyView markAllergy(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId,
            @PathVariable UUID entryId, @Valid @RequestBody EntryStatusRequest request) {
        requireEnteredInError(request);
        return service.markAllergyInError(actor, patientId, entryId);
    }

    @GetMapping("/medical-history")
    @PreAuthorize("hasAuthority('patient:read')")
    List<HistoryView> history(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId) {
        return service.listHistory(actor, patientId);
    }

    @PostMapping("/medical-history")
    @PreAuthorize("hasAuthority('patient:update-clinical')")
    @ResponseStatus(HttpStatus.CREATED)
    HistoryView addHistory(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId,
            @Valid @RequestBody CreateHistoryRequest request) {
        return service.addHistory(actor, patientId, request.condition(), request.notes());
    }

    @PatchMapping("/medical-history/{entryId}")
    @PreAuthorize("hasAuthority('patient:update-clinical')")
    HistoryView markHistory(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID patientId,
            @PathVariable UUID entryId, @Valid @RequestBody EntryStatusRequest request) {
        requireEnteredInError(request);
        return service.markHistoryInError(actor, patientId, entryId);
    }

    private static void requireEnteredInError(EntryStatusRequest request) {
        if (!"ENTERED_IN_ERROR".equals(request.status())) {
            throw new BusinessRuleException("VALIDATION_ERROR", "status must be ENTERED_IN_ERROR.");
        }
    }
}
