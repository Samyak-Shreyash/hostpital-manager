package com.hms.bedmanagement.web;

import com.hms.bedmanagement.application.BedService;
import com.hms.bedmanagement.application.BedView;
import com.hms.bedmanagement.application.WardService;
import com.hms.bedmanagement.application.WardView;
import com.hms.shared.PageResult;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Wards and their beds (api-design.md section 10). No DELETE: retire with PATCH {@code status}. */
@RestController
@RequestMapping("/api/v1/wards")
class WardController {

    private final WardService wards;
    private final BedService beds;

    WardController(WardService wards, BedService beds) {
        this.wards = wards;
        this.beds = beds;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('ward:read')")
    PageResult<WardView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return wards.list(actor, status, page, size);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ward:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    WardView create(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody CreateWardRequest request) {
        return wards.create(actor, request.code(), request.name(), request.departmentId(),
                request.careType());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('ward:manage')")
    WardView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateWardRequest request) {
        return wards.update(actor, id, request.version(), request.name(), request.departmentId(),
                request.status(), request.careType());
    }

    @GetMapping("/{id}/beds")
    @PreAuthorize("hasAuthority('bed:read')")
    PageResult<BedView> beds(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(required = false) @Pattern(regexp = "ACTIVE|OUT_OF_SERVICE") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return beds.list(actor, id, status, page, size);
    }

    @PostMapping("/{id}/beds")
    @PreAuthorize("hasAuthority('bed:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    BedView createBed(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody CreateBedRequest request) {
        return beds.create(actor, id, request.bedNo(), request.bedClass(), request.dailyRateServiceId());
    }
}
