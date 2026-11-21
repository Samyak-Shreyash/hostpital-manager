package com.hms.bedmanagement.web;

import com.hms.bedmanagement.application.AvailabilityView;
import com.hms.bedmanagement.application.BedService;
import com.hms.bedmanagement.application.BedView;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Beds by id and the availability board (api-design.md section 10). */
@RestController
@RequestMapping("/api/v1/beds")
class BedController {

    private final BedService beds;

    BedController(BedService beds) {
        this.beds = beds;
    }

    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('bed:read')")
    AvailabilityView availability(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) UUID wardId) {
        return beds.availability(actor, wardId);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('bed:manage')")
    BedView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateBedRequest request) {
        return beds.update(actor, id, request.version(), request.bedClass(), request.dailyRateServiceId(),
                request.status());
    }
}
