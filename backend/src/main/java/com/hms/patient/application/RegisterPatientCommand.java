package com.hms.patient.application;

import java.time.LocalDate;

/** A validated registration request. Exactly one of {@code dateOfBirth} / {@code ageYears} is given. */
public record RegisterPatientCommand(String fullName, String sex, LocalDate dateOfBirth, Integer ageYears,
        boolean dobEstimated, String phone, Address address, EmergencyContact emergencyContact) {

    public record Address(String line1, String line2, String city, String state, String pincode) {
    }

    public record EmergencyContact(String name, String relationship, String phone) {
    }
}
