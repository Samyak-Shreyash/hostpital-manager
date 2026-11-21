package com.hms.bedmanagement.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

record CreateBedRequest(
        @NotBlank @Size(max = 20) String bedNo,
        @Size(max = 50) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String bedClass,
        @NotNull UUID dailyRateServiceId) {
}
