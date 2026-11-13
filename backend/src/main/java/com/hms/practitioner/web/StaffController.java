package com.hms.practitioner.web;

import com.hms.practitioner.application.StaffService;
import com.hms.practitioner.application.StaffView;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff records ([E4-2], api-design.md section 5). Reuses the practitioner:* permissions. There is no delete
 * (deactivate with {@code status: INACTIVE}) and no role endpoint: roles are assigned on the linked user via
 * {@code PATCH /api/v1/users/{userId}} ([E1-7]).
 */
@RestController
@RequestMapping("/api/v1/staff")
class StaffController {

    private final StaffService staff;

    StaffController(StaffService staff) {
        this.staff = staff;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('practitioner:create')")
    @ResponseStatus(HttpStatus.CREATED)
    StaffView register(@AuthenticationPrincipal OrganizationPrincipal actor,
            @Valid @RequestBody StaffRequests.Create request) {
        return staff.register(actor, request.toCommand());
    }

    @GetMapping
    @PreAuthorize("hasAuthority('practitioner:read')")
    PageResult<StaffView> list(@AuthenticationPrincipal OrganizationPrincipal actor,
            @RequestParam(required = false) String staffType, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return staff.list(actor, staffType, departmentId, q, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('practitioner:read')")
    StaffView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return staff.get(actor, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('practitioner:update')")
    StaffView update(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody StaffRequests.Update request) {
        return staff.update(actor, id, request.toCommand());
    }
}
