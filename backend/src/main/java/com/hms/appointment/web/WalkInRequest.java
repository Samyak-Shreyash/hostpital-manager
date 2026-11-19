package com.hms.appointment.web;

import java.util.UUID;

/** A walk-in: the ids are checked by the service (422), {@code reason} is free text (at most 500, never audited). */
record WalkInRequest(UUID patientId, UUID doctorId, String reason) {
}
