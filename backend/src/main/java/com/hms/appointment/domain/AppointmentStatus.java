package com.hms.appointment.domain;

import java.util.Set;

/** Appointment lifecycle (domain-model.md section 6). RESCHEDULED is terminal: the successor is a new appointment. */
public enum AppointmentStatus {
    SCHEDULED, CHECKED_IN, IN_CONSULTATION, COMPLETED, CANCELLED, NO_SHOW, RESCHEDULED;

    public boolean canTransitionTo(AppointmentStatus target) {
        return successors().contains(target);
    }

    public boolean isTerminal() {
        return successors().isEmpty();
    }

    private Set<AppointmentStatus> successors() {
        return switch (this) {
            case SCHEDULED -> Set.of(CHECKED_IN, CANCELLED, NO_SHOW, RESCHEDULED);
            case CHECKED_IN -> Set.of(IN_CONSULTATION, CANCELLED);
            case IN_CONSULTATION -> Set.of(COMPLETED);
            case COMPLETED, CANCELLED, NO_SHOW, RESCHEDULED -> Set.of();
        };
    }
}
