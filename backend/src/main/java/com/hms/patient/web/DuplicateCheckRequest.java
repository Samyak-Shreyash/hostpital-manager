package com.hms.patient.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** POST /patients/duplicate-check body: the same fields the registration check uses (BR-P3). */
record DuplicateCheckRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotNull @PastOrPresent LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = CreatePatientRequest.Phone.REGEX, message = CreatePatientRequest.Phone.MESSAGE)
        String phone) {

    /** PHI: never printed. */
    @Override
    public String toString() {
        return "DuplicateCheckRequest[<redacted>]";
    }
}
