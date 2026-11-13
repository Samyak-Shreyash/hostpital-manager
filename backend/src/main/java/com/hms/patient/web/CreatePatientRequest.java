package com.hms.patient.web;

import com.hms.patient.application.RegisterPatientCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** POST /patients body (api-design.md section 6). {@code confirmNotDuplicate} + {@code overrideReason}: [E3-3]. */
record CreatePatientRequest(
        @NotBlank @Size(min = 2, max = 120) String fullName,
        @NotBlank @Pattern(regexp = "[MFOU]", message = "must be one of M, F, O, U") String sex,
        LocalDate dateOfBirth,
        Integer ageYears,
        Boolean dobEstimated,
        @NotBlank @Pattern(regexp = Phone.REGEX, message = Phone.MESSAGE) String phone,
        @Valid AddressRequest address,
        @Valid ContactRequest emergencyContact,
        @Pattern(regexp = Abha.REGEX, message = Abha.MESSAGE) String abhaNumber,
        Boolean confirmNotDuplicate,
        @Size(max = 500) String overrideReason) {

    /** ABHA number: 14 digits, optionally grouped 2-4-4-4 with hyphens. Format only, no checksum/ABDM check. */
    static final class Abha {
        static final String REGEX = "[0-9]{14}|[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{4}";
        static final String MESSAGE = "must be 14 digits";

        private Abha() {
        }
    }

    /** E.164, or a bare 10-digit Indian number. */
    static final class Phone {
        static final String REGEX = "\\+[1-9][0-9]{7,14}|[0-9]{10}";
        static final String MESSAGE = "must be E.164 (+<country><number>) or a 10-digit Indian number";

        private Phone() {
        }
    }

    record AddressRequest(
            @NotBlank @Size(max = 150) String line1,
            @Size(max = 150) String line2,
            @NotBlank @Size(max = 80) String city,
            @NotBlank @Size(max = 80) String state,
            @NotBlank @Pattern(regexp = "[0-9]{6}", message = "must be 6 digits") String pincode) {
    }

    record ContactRequest(
            @NotBlank @Size(min = 2, max = 120) String name,
            @NotBlank @Size(max = 50) String relationship,
            @NotBlank @Pattern(regexp = Phone.REGEX, message = Phone.MESSAGE) String phone) {
    }

    /** Never prints the ABHA number or the override reason (they would otherwise reach logs via toString). */
    @Override
    public String toString() {
        return "CreatePatientRequest[abhaNumber=" + (abhaNumber == null ? "null" : "<redacted>")
                + ", overrideReason=" + (overrideReason == null ? "null" : "<redacted>") + ", ...]";
    }

    RegisterPatientCommand toCommand() {
        return new RegisterPatientCommand(fullName, sex, dateOfBirth, ageYears, Boolean.TRUE.equals(dobEstimated),
                phone,
                address == null ? null : new RegisterPatientCommand.Address(address.line1(), address.line2(),
                        address.city(), address.state(), address.pincode()),
                emergencyContact == null ? null : new RegisterPatientCommand.EmergencyContact(
                        emergencyContact.name(), emergencyContact.relationship(), emergencyContact.phone()),
                abhaNumber, Boolean.TRUE.equals(confirmNotDuplicate), overrideReason);
    }
}
