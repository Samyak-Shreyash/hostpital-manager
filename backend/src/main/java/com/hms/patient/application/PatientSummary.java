package com.hms.patient.application;

import java.time.LocalDate;
import java.util.UUID;

/** One row of a patient search result (api-design.md section 6). */
public record PatientSummary(UUID id, String uhid, String fullName, String sex, LocalDate dateOfBirth, String phone,
        String status) {
}
