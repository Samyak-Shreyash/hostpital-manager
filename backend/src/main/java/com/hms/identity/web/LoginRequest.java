package com.hms.identity.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Password length is checked loosely here; the password policy applies when a password is set ([E1-4]). */
record LoginRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String password) {

    /** Never print the password, e.g. in a debug log of the request. */
    @Override
    public String toString() {
        return "LoginRequest[email=***, password=***]";
    }
}
