package com.hms.clinical.web;

import com.hms.clinical.application.EncounterService;
import com.hms.clinical.application.EncounterService.StartCommand;
import com.hms.clinical.application.EncounterView;
import com.hms.clinical.application.EncounterView.Summary;
import com.hms.clinical.web.EncounterRequests.DiagnosesRequest;
import com.hms.clinical.web.EncounterRequests.FollowUpRequest;
import com.hms.clinical.web.EncounterRequests.ObservationsRequest;
import com.hms.clinical.web.EncounterRequests.StartEncounterRequest;
import com.hms.clinical.web.EncounterRequests.TextRequest;
import com.hms.identity.api.Permissions;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Encounters (api-design.md section 8). [E6-1]: start, read, history, observations, notes, diagnoses, plan, follow-up. */
@RestController
@RequestMapping("/api/v1")
class EncounterController {

    private final EncounterService encounters;

    EncounterController(EncounterService encounters) {
        this.encounters = encounters;
    }

    @PostMapping("/encounters")
    @PreAuthorize("hasAuthority('encounter:create')")
    @ResponseStatus(HttpStatus.CREATED)
    EncounterView start(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @RequestBody StartEncounterRequest request) {
        return encounters.start(actor, new StartCommand(request.appointmentId(), request.patientId(),
                request.chiefComplaint(), mayEditAny(authentication)));
    }

    @GetMapping("/encounters/{id}")
    @PreAuthorize("hasAuthority('encounter:read')")
    EncounterView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return encounters.get(actor, id);
    }

    @GetMapping("/patients/{id}/encounters")
    @PreAuthorize("hasAuthority('encounter:read')")
    PageResult<Summary> history(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return encounters.history(actor, id, page, size);
    }

    /**
     * Vitals are also recorded by nursing roles that hold {@code observation:write} without {@code encounter:update}:
     * for them the treating-practitioner rule does not apply. Without {@code encounter:read} the response is the
     * reduced {@link ObservationsResult} (what the caller just wrote), never the rest of the clinical record; with it,
     * the full {@link EncounterView}. No read audit is written for the reduced body.
     */
    @PutMapping("/encounters/{id}/observations")
    @PreAuthorize("hasAuthority('observation:write')")
    Object observations(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody ObservationsRequest request) {
        boolean support = mayEditAny(authentication) || !has(authentication, Permissions.ENCOUNTER_UPDATE);
        EncounterView view = encounters.replaceObservations(actor, id, request.version(), request.observations(),
                support);
        if (has(authentication, Permissions.ENCOUNTER_READ)) {
            return view;
        }
        return new ObservationsResult(view.id(), view.version(), view.observations(), view.warnings());
    }

    /** Reduced PUT observations body for callers without {@code encounter:read}. */
    record ObservationsResult(UUID id, long version, List<EncounterView.Observation> observations,
            List<EncounterView.Warning> warnings) {
    }

    @PutMapping("/encounters/{id}/notes/{kind}")
    @PreAuthorize("hasAuthority('encounter:update')")
    EncounterView note(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @PathVariable String kind, @RequestBody TextRequest request) {
        return encounters.putNote(actor, id, kind, request.version(), request.text(), mayEditAny(authentication));
    }

    @PutMapping("/encounters/{id}/diagnoses")
    @PreAuthorize("hasAuthority('encounter:update')")
    EncounterView diagnoses(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody DiagnosesRequest request) {
        return encounters.replaceDiagnoses(actor, id, request.version(), request.diagnoses(),
                mayEditAny(authentication));
    }

    @PutMapping("/encounters/{id}/treatment-plan")
    @PreAuthorize("hasAuthority('encounter:update')")
    EncounterView treatmentPlan(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody TextRequest request) {
        return encounters.putTreatmentPlan(actor, id, request.version(), request.text(), mayEditAny(authentication));
    }

    @PutMapping("/encounters/{id}/follow-up")
    @PreAuthorize("hasAuthority('encounter:update')")
    EncounterView followUp(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody FollowUpRequest request) {
        return encounters.putFollowUp(actor, id, request.version(), request.date(), request.instructions(),
                mayEditAny(authentication));
    }

    private static boolean mayEditAny(Authentication authentication) {
        return has(authentication, Permissions.ENCOUNTER_UPDATE_ANY);
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }
}
