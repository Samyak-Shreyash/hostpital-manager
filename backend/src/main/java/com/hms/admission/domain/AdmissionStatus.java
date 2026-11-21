package com.hms.admission.domain;

import java.util.Set;

/**
 * Admission lifecycle (domain-model.md; plan ruling R6). {@code DISCHARGE_INITIATED} may go back to
 * {@code ADMITTED} (discharge called off); {@code DISCHARGED} and {@code CANCELLED} are terminal.
 */
public enum AdmissionStatus {
    ADMITTED, DISCHARGE_INITIATED, DISCHARGED, CANCELLED;

    public boolean canTransitionTo(AdmissionStatus target) {
        return successors().contains(target);
    }

    /** Holds a bed and counts for "one active admission per patient" (I3). */
    public boolean isActive() {
        return this == ADMITTED || this == DISCHARGE_INITIATED;
    }

    private Set<AdmissionStatus> successors() {
        return switch (this) {
            case ADMITTED -> Set.of(DISCHARGE_INITIATED, CANCELLED);
            case DISCHARGE_INITIATED -> Set.of(ADMITTED, DISCHARGED);
            case DISCHARGED, CANCELLED -> Set.of();
        };
    }
}
