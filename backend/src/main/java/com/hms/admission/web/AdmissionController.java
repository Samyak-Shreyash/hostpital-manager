package com.hms.admission.web;

import com.hms.admission.application.AdmissionChargeSource;
import com.hms.admission.application.AdmissionChargeSource.ChargesPreview;
import com.hms.admission.application.AdmissionService;
import com.hms.admission.application.AdmissionService.AdmitCommand;
import com.hms.admission.application.AdmissionService.TransferView;
import com.hms.admission.application.AdmissionView;
import com.hms.admission.application.DischargeService;
import com.hms.admission.application.DischargeService.DischargeCommand;
import com.hms.admission.application.DischargeService.DischargeResult;
import com.hms.admission.application.DischargeService.Medication;
import com.hms.admission.application.DischargeService.SummaryCommand;
import com.hms.admission.application.DischargeService.SummaryView;
import com.hms.identity.api.Permissions;
import com.hms.bedmanagement.api.BedAllocation.AssignmentView;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import com.hms.shared.domain.BusinessRuleException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
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

/** Admissions (api-design.md section 11). [E8-2] [E8-3]: admit with a bed, list, read, update, cancel, bed history. */
@RestController
@RequestMapping("/api/v1/admissions")
class AdmissionController {

    private final AdmissionService admissions;
    private final AdmissionChargeSource charges;
    private final DischargeService discharges;

    AdmissionController(AdmissionService admissions, AdmissionChargeSource charges, DischargeService discharges) {
        this.admissions = admissions;
        this.charges = charges;
        this.discharges = discharges;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('admission:create')")
    @ResponseStatus(HttpStatus.CREATED)
    AdmissionView admit(@AuthenticationPrincipal OrganizationPrincipal actor, @RequestBody AdmitRequest request) {
        return admissions.admit(actor, new AdmitCommand(request.patientId(), request.admittingDoctorId(),
                request.bedId(), request.admissionDiagnosis(), request.sourceEncounterId()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('admission:read')")
    PageResult<AdmissionView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) String status, @RequestParam(required = false) UUID wardId,
            @RequestParam(required = false) UUID patientId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return admissions.list(actor, status, wardId, patientId, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('admission:read')")
    AdmissionView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return admissions.get(actor, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('admission:update')")
    AdmissionView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody UpdateAdmissionRequest request) {
        return admissions.update(actor, id, request.version(), request.admissionDiagnosis());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('admission:cancel')")
    AdmissionView cancel(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody AdmissionVersionRequest request) {
        return admissions.cancel(actor, id, request.version());
    }

    @GetMapping("/{id}/bed-assignments")
    @PreAuthorize("hasAuthority('admission:read')")
    List<AssignmentView> bedAssignments(@AuthenticationPrincipal OrganizationPrincipal actor,
            @PathVariable UUID id) {
        return admissions.bedAssignments(actor, id);
    }

    @PostMapping("/{id}/bed-assignments/transfer")
    @PreAuthorize("hasAuthority('bed:assign')")
    TransferView transfer(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody TransferRequest request) {
        return admissions.transfer(actor, id, request.toBedId(), request.reason());
    }

    /** [E8-5] Room-day charges so far (BR-B6 rule [A]); {@code asOf} (ISO-8601 instant, malformed -> 422) defaults to now. */
    @GetMapping("/{id}/charges-preview")
    @PreAuthorize("hasAuthority('invoice:read')")
    ChargesPreview chargesPreview(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(required = false) String asOf) {
        Instant at;
        try {
            at = asOf == null || asOf.isBlank() ? null : Instant.parse(asOf);
        } catch (DateTimeParseException e) {
            throw new BusinessRuleException("VALIDATION_ERROR", "asOf must be an ISO-8601 instant.");
        }
        return charges.preview(actor, id, at);
    }

    /** [E8-7] Creates or replaces the discharge summary; the first save moves the admission to DISCHARGE_INITIATED. */
    @PutMapping("/{id}/discharge-summary")
    @PreAuthorize("hasAuthority('discharge:write')")
    SummaryView saveSummary(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestBody SummaryRequest request) {
        return discharges.saveSummary(actor, id, new SummaryCommand(request.version(), request.finalDiagnosis(),
                request.courseInHospital(), request.procedures(), request.conditionAtDischarge(),
                request.medications(), request.followUp(), request.plannedDischargeAt()));
    }

    @GetMapping("/{id}/discharge-summary")
    @PreAuthorize("hasAuthority('admission:read')")
    SummaryView summary(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return discharges.getSummary(actor, id);
    }

    /** [E8-7] DISCHARGE_INITIATED to DISCHARGED in one transaction, releasing the bed at {@code dischargedAt}. */
    @PostMapping("/{id}/discharge")
    @PreAuthorize("hasAuthority('discharge:complete')")
    DischargeResult discharge(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody DischargeRequest request) {
        boolean mayDefer = authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.DISCHARGE_DEFER_BILLING.equals(a.getAuthority()));
        return discharges.discharge(actor, id, new DischargeCommand(request.version(), request.dischargedAt(),
                request.invoiceId(), Boolean.TRUE.equals(request.deferBilling())), mayDefer);
    }

    /** Clinical free text: never audited or logged. {@code finalDiagnosis}, {@code conditionAtDischarge} and
     * {@code plannedDischargeAt} are required; {@code version} is required once a summary exists. */
    record SummaryRequest(Long version, String finalDiagnosis, String courseInHospital, String procedures,
            String conditionAtDischarge, List<Medication> medications, String followUp, Instant plannedDischargeAt) {
    }

    record DischargeRequest(Long version, Instant dischargedAt, UUID invoiceId, Boolean deferBilling) {
    }

    /** {@code reason} is required (1 to 500 characters); stored on the new assignment, never audited. */
    record TransferRequest(UUID toBedId, String reason) {
    }

    record AdmitRequest(UUID patientId, UUID admittingDoctorId, UUID bedId, String admissionDiagnosis,
            UUID sourceEncounterId) {
    }

    /** {@code admissionDiagnosis} is required (1 to 1000 characters); its value is never audited or logged. */
    record UpdateAdmissionRequest(Long version, String admissionDiagnosis) {
    }

    record AdmissionVersionRequest(Long version) {
    }
}
