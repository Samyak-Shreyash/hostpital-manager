package com.hms.appointment.application;

import java.time.Instant;
import java.util.UUID;

/** An appointment as the API returns it (api-design.md section 7). {@code doctorId} is the practitioner id. */
public record AppointmentView(UUID id, UUID patientId, UUID doctorId, Instant startAt, Instant endAt,
        int durationMinutes, String type, String status, String reason, boolean outsideAvailability, long version,
        Instant createdAt, Instant updatedAt, UUID rescheduledFromId, String cancelReason,
        Instant cancelledAt) {
}
