package com.hms.identity.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Partial update: omitted fields are left alone. {@code roleCodes} replaces the whole role set when present. */
record UpdateUserRequest(
        @NotNull Long version,
        @Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String fullName,
        @Size(max = 20) List<@NotNull @Size(max = 50) String> roleCodes) {
}
