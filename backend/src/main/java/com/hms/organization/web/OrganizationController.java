package com.hms.organization.web;

import com.hms.organization.application.OrganizationSettingsService;
import com.hms.organization.application.OrganizationView;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own organization (api-design.md section 4). No id in the path: the organization is the principal's. */
@RestController
@RequestMapping("/api/v1/organization")
class OrganizationController {

    private final OrganizationSettingsService settings;

    OrganizationController(OrganizationSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('org:read')")
    OrganizationView get(@AuthenticationPrincipal OrganizationPrincipal actor) {
        return settings.get(actor);
    }

    @PatchMapping
    @PreAuthorize("hasAuthority('org:update')")
    OrganizationView update(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody UpdateOrganizationRequest request) {
        return settings.update(actor, request.version(), request.name(), request.timezone(), request.gstin(),
                request.address());
    }
}
