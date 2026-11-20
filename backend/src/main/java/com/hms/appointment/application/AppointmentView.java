package com.hms.appointment.application;

import com.hms.patient.api.PatientDirectory.PatientBrief;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An appointment as the API returns it (api-design.md section 7). {@code doctorId} is the practitioner id; {@code patient} is the UHID and name only (no patient read is audited for it). */
public record AppointmentView(UUID id, UUID patientId, UUID doctorId, Instant startAt, Instant endAt,
        int durationMinutes, String type, String status, String reason, boolean outsideAvailability, long version,
        Instant createdAt, Instant updatedAt, UUID rescheduledFromId, String cancelReason,
        Instant cancelledAt, Integer tokenNo, LocalDate tokenDate, PatientBrief patient) {

    AppointmentView withPatient(PatientBrief brief) {
        return new AppointmentView(id, patientId, doctorId, startAt, endAt, durationMinutes, type, status, reason,
                outsideAvailability, version, createdAt, updatedAt, rescheduledFromId, cancelReason, cancelledAt,
                tokenNo, tokenDate, brief);
    }
}
