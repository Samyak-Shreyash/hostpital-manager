package com.hms.appointment.web;

import java.time.Instant;

/** Required fields are checked by the service, so a missing one answers 422 VALIDATION_ERROR. */
record RescheduleAppointmentRequest(Long version, Instant startAt, Integer durationMinutes) {
}
