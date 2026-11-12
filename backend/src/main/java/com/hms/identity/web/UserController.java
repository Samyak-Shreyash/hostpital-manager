package com.hms.identity.web;

import com.hms.identity.api.AuthenticatedPrincipal;
import com.hms.shared.PageResult;
import com.hms.identity.application.UserManagementService;
import com.hms.identity.application.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** User management (api-design.md section 3). The organization is always the caller's, never request input. */
@RestController
@RequestMapping("/api/v1/users")
class UserController {

    private final UserManagementService users;

    UserController(UserManagementService users) {
        this.users = users;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('user:read')")
    PageResult<UserResponse> list(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @RequestParam(required = false) String status, @RequestParam(required = false) String role,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<UserView> result = users.list(actor, status, role, q, page, size);
        return new PageResult<>(result.items().stream().map(UserResponse::of).toList(), result.page(),
                result.size(), result.totalItems());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('user:create')")
    ResponseEntity<UserResponse> create(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @Valid @RequestBody CreateUserRequest request) {
        UserResponse body = UserResponse.of(
                users.create(actor, request.email(), request.fullName(), request.roleCodes()));
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('user:read')")
    UserResponse get(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable UUID id) {
        return UserResponse.of(users.get(actor, id));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasAuthority('user:update')")
    UserResponse update(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody UpdateUserRequest request, HttpServletRequest httpRequest) {
        return UserResponse.of(users.update(actor, id, request.version(), request.fullName(), request.roleCodes(),
                httpRequest.getSession().getId()));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('user:update')")
    UserResponse activate(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable UUID id) {
        return UserResponse.of(users.setActive(actor, id, true));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('user:update')")
    UserResponse deactivate(@AuthenticationPrincipal AuthenticatedPrincipal actor, @PathVariable UUID id) {
        return UserResponse.of(users.setActive(actor, id, false));
    }

    /** The temporary password is in this response only; it is never retrievable again. */
    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:reset-password')")
    ResponseEntity<UserResponse> resetPassword(@AuthenticationPrincipal AuthenticatedPrincipal actor,
            @PathVariable UUID id, HttpServletRequest httpRequest) {
        UserResponse body = UserResponse.of(users.resetPassword(actor, id, httpRequest.getSession().getId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
