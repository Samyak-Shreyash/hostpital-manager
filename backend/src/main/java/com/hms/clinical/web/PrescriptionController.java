package com.hms.clinical.web;

import com.hms.clinical.application.PrescriptionService;
import com.hms.clinical.application.PrescriptionService.ItemInput;
import com.hms.clinical.application.PrescriptionView;
import com.hms.identity.api.Permissions;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Prescriptions (api-design.md section 9). [E6-3]: draft, replace items, issue, revise, read. */
@RestController
@RequestMapping("/api/v1")
class PrescriptionController {

    /** The body of PUT: the caller's {@code version} and the complete item list. */
    record ItemsRequest(Long version, List<ItemInput> items) {
    }

    /** The body of issue: {@code acknowledgeAllergyWarnings} is required to be true when warnings exist. */
    record IssueRequest(Long version, Boolean acknowledgeAllergyWarnings) {
    }

    private final PrescriptionService prescriptions;

    PrescriptionController(PrescriptionService prescriptions) {
        this.prescriptions = prescriptions;
    }

    @PostMapping("/encounters/{id}/prescriptions")
    @PreAuthorize("hasAuthority('prescription:create')")
    @ResponseStatus(HttpStatus.CREATED)
    PrescriptionView create(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id) {
        return prescriptions.createDraft(actor, id, mayEditAny(authentication));
    }

    @PutMapping("/prescriptions/{id}")
    @PreAuthorize("hasAuthority('prescription:create')")
    PrescriptionView items(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody ItemsRequest request) {
        return prescriptions.replaceItems(actor, id, request.version(), request.items(), mayEditAny(authentication));
    }

    @PostMapping("/prescriptions/{id}/issue")
    @PreAuthorize("hasAuthority('prescription:issue')")
    PrescriptionView issue(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id, @RequestBody IssueRequest request) {
        return prescriptions.issue(actor, id, request.version(), request.acknowledgeAllergyWarnings(),
                mayEditAny(authentication));
    }

    @PostMapping("/prescriptions/{id}/revise")
    @PreAuthorize("hasAuthority('prescription:issue')")
    @ResponseStatus(HttpStatus.CREATED)
    PrescriptionView revise(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @PathVariable UUID id) {
        return prescriptions.revise(actor, id, mayEditAny(authentication));
    }

    @GetMapping("/prescriptions/{id}")
    @PreAuthorize("hasAuthority('prescription:read')")
    PrescriptionView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return prescriptions.get(actor, id);
    }

    private static boolean mayEditAny(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(a -> Permissions.ENCOUNTER_UPDATE_ANY.equals(a.getAuthority()));
    }
}
