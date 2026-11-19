package com.hms.appointment.api;

import java.util.Optional;
import java.util.UUID;

/** Read-only view of an appointment for the clinical module, which starts an encounter from it ([E6-1]). */
public interface AppointmentLookup {

    /** Empty for an unknown or other-organization appointment. */
    Optional<AppointmentRef> find(UUID organizationId, UUID appointmentId);

    /** {@code practitionerId} is the doctor; {@code status} is the appointment status name. */
    record AppointmentRef(UUID id, UUID patientId, UUID practitionerId, String status) {
    }
}
