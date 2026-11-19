package com.hms.appointment.web;

/** Optimistic-lock version of the appointment the caller last saw. */
record AppointmentVersionRequest(Long version) {
}
