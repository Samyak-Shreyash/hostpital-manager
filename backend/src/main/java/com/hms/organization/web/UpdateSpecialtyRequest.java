package com.hms.organization.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: the code never changes; {@code active=false} deactivates. */
record UpdateSpecialtyRequest(
        @NotNull Long version,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        Boolean active) {
}
