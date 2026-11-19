package com.hms.clinical.web;

import com.hms.clinical.application.EncounterService.DiagnosisInput;
import com.hms.clinical.application.EncounterService.ObservationInput;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Request bodies of the encounter endpoints; the service validates them (422) and never echoes free text. */
final class EncounterRequests {

    private EncounterRequests() {
    }

    /** {@code appointmentId} or, for an ad hoc encounter, {@code patientId}. */
    record StartEncounterRequest(UUID appointmentId, UUID patientId, String chiefComplaint) {
    }

    record ObservationsRequest(Long version, List<ObservationInput> observations) {
    }

    record TextRequest(Long version, String text) {
    }

    record DiagnosesRequest(Long version, List<DiagnosisInput> diagnoses) {
    }

    record FollowUpRequest(Long version, LocalDate date, String instructions) {
    }
}
