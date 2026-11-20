package com.hms.clinical.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** An encounter as the API returns it (api-design.md section 8). Money-like decimals are strings. */
public record EncounterView(UUID id, PatientRef patient, UUID practitionerId, UUID appointmentId, String status,
        String chiefComplaint, long version, Instant startedAt, Instant finalizedAt, List<Observation> observations,
        List<Warning> warnings, List<Note> notes, List<Diagnosis> diagnoses, String treatmentPlan,
        FollowUp followUp, List<PrescriptionRef> prescriptions) {

    /** The same view with the prescription summary replaced (callers without prescription:read get none). */
    public EncounterView withPrescriptions(List<PrescriptionRef> refs) {
        return new EncounterView(id, patient, practitionerId, appointmentId, status, chiefComplaint, version, startedAt,
                finalizedAt, observations, warnings, notes, diagnoses, treatmentPlan, followUp, refs);
    }

    /** A prescription of this encounter by id, status and version number; newest first, superseded ones included. */
    public record PrescriptionRef(UUID id, String status, int versionNo) {
    }

    /** The patient by id and business key; never the name or contact details. */
    public record PatientRef(UUID id, String uhid) {
    }

    public record Observation(String kind, String value, String unit) {
    }

    /** A value outside its plausible range (accepted, not blocked). */
    public record Warning(String kind, String message) {
    }

    public record Note(String kind, String text, UUID authorId, Instant createdAt) {
    }

    public record Diagnosis(String code, String description, boolean primary) {
    }

    public record FollowUp(LocalDate date, String instructions) {
    }

    /** A line of a patient's encounter history. */
    public record Summary(UUID id, UUID practitionerId, UUID appointmentId, String status, String chiefComplaint,
            Instant startedAt, Instant finalizedAt) {
    }
}
