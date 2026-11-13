package com.hms.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The password policy itself is enforced by the application layer (422 PASSWORD_POLICY_VIOLATION). */
record ChangePasswordRequest(
        @NotBlank @Size(max = 128) String currentPassword,
        @NotBlank @Size(max = 1000) String newPassword) {

    /** Never print passwords, e.g. in a debug log of the request. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=***, newPassword=***]";
    }
}
