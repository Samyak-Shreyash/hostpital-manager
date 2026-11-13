package com.hms.patient.application;

import java.time.LocalDate;

/**
 * A validated partial update ([E3-5], PATCH semantics): null = leave unchanged. {@code abhaNumber} "" clears the
 * number. {@code uhid} is carried only so that an attempt to change it is rejected. {@code identityChangeReason} is
 * required when the name, sex, date of birth or dobEstimated changes; {@code confirmNotDuplicate} with
 * {@code overrideReason} accepts suspected duplicates after an identity/phone edit. Free text: never log it.
 */
public record UpdatePatientCommand(long version, String uhid, String fullName, String sex, LocalDate dateOfBirth,
        Boolean dobEstimated, String phone, RegisterPatientCommand.Address address,
        RegisterPatientCommand.EmergencyContact emergencyContact, String abhaNumber, String identityChangeReason,
        boolean confirmNotDuplicate, String overrideReason) {

    /** Never prints PHI, in particular the ABHA number or the free-text reasons. */
    @Override
    public String toString() {
        return "UpdatePatientCommand[version=" + version + ", abhaNumber="
                + (abhaNumber == null ? "null" : "<redacted>") + ", identityChangeReason="
                + (identityChangeReason == null ? "null" : "<redacted>") + ", confirmNotDuplicate="
                + confirmNotDuplicate + ", ...]";
    }
}
