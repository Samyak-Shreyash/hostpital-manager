package com.hms.appointment.api;

import java.util.UUID;

/**
 * State changes the clinical module drives when a consultation starts and ends ([E5-3]; domain-model.md section 6).
 * They obey the appointment state machine (CHECKED_IN to IN_CONSULTATION to COMPLETED) and join the caller's
 * transaction. An unknown or other-organization appointment is {@code NotFoundException}; a move the state machine
 * forbids is {@code ConflictException} with code {@code INVALID_STATE_TRANSITION}.
 */
public interface AppointmentLifecycle {

    void markInConsultation(UUID organizationId, UUID appointmentId);

    void markCompleted(UUID organizationId, UUID appointmentId);
}
