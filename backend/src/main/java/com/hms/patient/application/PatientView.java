package com.hms.patient.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A patient as returned by the API (api-design.md section 6). Business key for people is {@code uhid}. The ABHA
 * number is never returned: only its last 4 digits ({@code abhaLast4}, null when none was captured).
 */
public record PatientView(UUID id, String uhid, String fullName, String sex, LocalDate dateOfBirth,
        boolean dobEstimated, String phone, String abhaLast4, AddressView address, ContactView emergencyContact,
        String status,
        long version, Instant createdAt, Instant updatedAt) {

    public record AddressView(String line1, String line2, String city, String state, String pincode) {
    }

    public record ContactView(String name, String relationship, String phone) {
    }
}
