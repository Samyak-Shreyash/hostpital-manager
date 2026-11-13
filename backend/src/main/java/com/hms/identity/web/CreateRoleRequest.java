package com.hms.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

record CreateRoleRequest(
        @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{0,49}", message = "must be upper snake case, up to 50 characters")
        String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(max = 200) List<@NotBlank @Size(max = 50) String> permissionCodes) {
}
