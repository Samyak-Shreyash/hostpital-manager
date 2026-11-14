package com.hms.patient.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** POST /patients/search-by-abha body. The number goes in the body, never the URL. */
record SearchByAbhaRequest(
        @NotBlank @Pattern(regexp = CreatePatientRequest.Abha.REGEX, message = CreatePatientRequest.Abha.MESSAGE)
        String abhaNumber) {

    /** Never prints the ABHA number. */
    @Override
    public String toString() {
        return "SearchByAbhaRequest[abhaNumber=" + (abhaNumber == null ? "null" : "<redacted>") + "]";
    }
}
