package com.hms.bedmanagement.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Partial update: the code never changes; {@code status=INACTIVE} retires the ward. */
record UpdateWardRequest(
        @NotNull Long version,
        @Size(max = 100) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        UUID departmentId,
        @Pattern(regexp = "ACTIVE|INACTIVE", message = "must be ACTIVE or INACTIVE") String status,
        @Pattern(regexp = "GENERAL|CRITICAL_CARE", message = "must be GENERAL or CRITICAL_CARE") String careType) {
}
