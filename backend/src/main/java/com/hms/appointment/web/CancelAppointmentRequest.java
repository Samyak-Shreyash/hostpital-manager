package com.hms.appointment.web;

/** {@code reason} is required (1 to 500 characters), checked by the service; it is never audited or logged. */
record CancelAppointmentRequest(Long version, String reason) {
}
