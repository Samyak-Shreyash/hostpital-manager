package com.hms.bedmanagement.web;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Partial update: bed number and ward never change. */
record UpdateBedRequest(
        @NotNull Long version,
        @Size(max = 50) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String bedClass,
        UUID dailyRateServiceId,
        @Pattern(regexp = "ACTIVE|OUT_OF_SERVICE", message = "must be ACTIVE or OUT_OF_SERVICE") String status) {
}
