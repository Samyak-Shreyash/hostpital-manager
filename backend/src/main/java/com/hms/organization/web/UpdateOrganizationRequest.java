package com.hms.organization.web;

import com.hms.organization.api.OrganizationRules;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial update: omitted fields are left alone; an empty {@code gstin} or {@code address} clears it. */
record UpdateOrganizationRequest(
        @NotNull Long version,
        @Size(max = 200) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 64) String timezone,
        @Pattern(regexp = "|" + OrganizationRules.GSTIN_REGEX, message = "must be a valid GSTIN") String gstin,
        @Size(max = 500) String address) {
}
