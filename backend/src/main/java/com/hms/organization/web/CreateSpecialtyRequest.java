package com.hms.organization.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record CreateSpecialtyRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,20}", message = "must be 2-20 characters of A-Z, 0-9 or _") String code,
        @NotBlank @Size(max = 100) String name) {
}
