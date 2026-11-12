package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.identity.application.RoleManagementService;
import com.hms.identity.application.RoleView;
import jakarta.servlet.http.HttpServletRequest;
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

/** Roles and the permission catalogue (api-design.md section 3). */
@RestController
@RequestMapping("/api/v1")
class RoleController {

    private final RoleManagementService roles;

    RoleController(RoleManagementService roles) {
        this.roles = roles;
    }

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('role:read')")
    List<RoleView> list(@AuthenticationPrincipal AuthenticatedPrincipal actor) {
        return roles.list(actor);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('role:read')")
    List<String> permissions() {
        return roles.permissions();
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('role:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    RoleView create(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @Valid @RequestBody CreateRoleRequest request) {
        return roles.create(actor, request.code(), request.name(), request.permissionCodes());
    }

    @PatchMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    RoleView update(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateRoleRequest request, HttpServletRequest httpRequest) {
        return roles.update(actor, id, request.version(), request.name(), request.permissionCodes(),
                httpRequest.getSession().getId());
    }
}
