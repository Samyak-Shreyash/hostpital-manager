package com.hms.appointment.web;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code startAt} is a UTC instant on a whole minute; {@code outsideAvailability} (default false) books outside the
 * doctor's availability and needs {@code appointment:override}. Required fields are checked by the service, so a
 * missing one answers 422 VALIDATION_ERROR like the other rules.
 */
record BookAppointmentRequest(UUID patientId, UUID doctorId, Instant startAt, Integer durationMinutes, String reason,
        Boolean outsideAvailability) {
}
