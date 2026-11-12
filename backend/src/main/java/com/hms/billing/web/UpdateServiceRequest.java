package com.hms.billing.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: the code never changes; {@code active=false} retires the service. */
record UpdateServiceRequest(
        @NotNull Long version,
        @Size(max = 150) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        Boolean active) {
}
