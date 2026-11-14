package com.hms.patient.application;

import java.time.LocalDate;

/**
 * A validated registration request. Exactly one of {@code dateOfBirth} / {@code ageYears} is given.
 * {@code abhaNumber} is optional: 14 digits, hyphens allowed; never log it. {@code confirmNotDuplicate} with an
 * {@code overrideReason} registers despite suspected duplicates (BR-P3); the reason is free text, never log it.
 */
public record RegisterPatientCommand(String fullName, String sex, LocalDate dateOfBirth, Integer ageYears,
        boolean dobEstimated, String phone, Address address, EmergencyContact emergencyContact, String abhaNumber,
        boolean confirmNotDuplicate, String overrideReason) {

    /** Never prints PHI, in particular the ABHA number or the override reason. */
    @Override
    public String toString() {
        return "RegisterPatientCommand[abhaNumber=" + (abhaNumber == null ? "null" : "<redacted>")
                + ", confirmNotDuplicate=" + confirmNotDuplicate + ", ...]";
    }

    public record Address(String line1, String line2, String city, String state, String pincode) {
    }

    public record EmergencyContact(String name, String relationship, String phone) {
    }
}
