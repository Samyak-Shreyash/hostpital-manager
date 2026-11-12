package com.hms.identity.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Partial update: {@code permissionCodes} replaces the whole permission set when present. */
record UpdateRoleRequest(
        @NotNull Long version,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 200) List<@NotNull @Size(max = 50) String> permissionCodes) {
}
