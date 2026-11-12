package com.hms.billing.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record CreateServiceRequest(
        @NotBlank @Pattern(regexp = "[A-Z0-9_]{2,30}", message = "must be 2-30 characters of A-Z, 0-9 or _") String code,
        @NotBlank @Size(max = 150) String name) {
}
