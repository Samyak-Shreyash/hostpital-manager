package com.hms.patient.web;

import com.hms.patient.application.RegisterPatientCommand;
import com.hms.patient.application.UpdatePatientCommand;
import com.hms.patient.web.CreatePatientRequest.AddressRequest;
import com.hms.patient.web.CreatePatientRequest.ContactRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * PATCH /patients/{id} body ([E3-5], api-design.md section 6). {@code version} is required; omitted fields are left
 * unchanged; {@code abhaNumber: ""} clears the ABHA number. {@code uhid} is accepted only to reject it.
 */
record UpdatePatientRequest(
        @NotNull Long version,
        String uhid,
        @Size(min = 2, max = 120) String fullName,
        @Pattern(regexp = "[MFOU]", message = "must be one of M, F, O, U") String sex,
        LocalDate dateOfBirth,
        Boolean dobEstimated,
        @Pattern(regexp = CreatePatientRequest.Phone.REGEX, message = CreatePatientRequest.Phone.MESSAGE)
        String phone,
        @Valid AddressRequest address,
        @Valid ContactRequest emergencyContact,
        @Pattern(regexp = "|" + CreatePatientRequest.Abha.REGEX, message = "must be 14 digits, or empty to clear")
        String abhaNumber,
        @Size(max = 500) String identityChangeReason,
        Boolean confirmNotDuplicate,
        @Size(max = 500) String overrideReason) {

    /** Never prints the ABHA number or the free-text reasons. */
    @Override
    public String toString() {
        return "UpdatePatientRequest[version=" + version + ", abhaNumber="
                + (abhaNumber == null ? "null" : "<redacted>") + ", ...]";
    }

    UpdatePatientCommand toCommand() {
        return new UpdatePatientCommand(version, uhid, fullName, sex, dateOfBirth, dobEstimated, phone,
                address == null ? null : new RegisterPatientCommand.Address(address.line1(), address.line2(),
                        address.city(), address.state(), address.pincode()),
                emergencyContact == null ? null : new RegisterPatientCommand.EmergencyContact(
                        emergencyContact.name(), emergencyContact.relationship(), emergencyContact.phone()),
                abhaNumber, identityChangeReason, Boolean.TRUE.equals(confirmNotDuplicate), overrideReason);
    }
}
