package com.hms.admission.application;

import com.hms.patient.api.PatientDirectory.PatientBrief;
import java.time.Instant;
import java.util.UUID;

/**
 * An admission as the API returns it (api-design.md section 11). {@code currentBed} is null once the bed is
 * released; {@code plannedDischargeAt} is set by the discharge summary ([E8-7]).
 */
public record AdmissionView(UUID id, String admissionNo, UUID patientId, PatientBrief patient,
        UUID admittingDoctorId, UUID sourceEncounterId, String admissionDiagnosis, String status, Instant admittedAt,
        Instant plannedDischargeAt, Instant dischargedAt, CurrentBed currentBed, long version, Instant createdAt,
        Instant updatedAt) {

    /** {@code ward} is the ward's name. */
    public record CurrentBed(UUID id, String bedNo, String ward) {
    }
}
