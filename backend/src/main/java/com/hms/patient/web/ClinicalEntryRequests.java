package com.hms.patient.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Request bodies for allergies and medical history ([E3-4]). Free text is never printed by {@code toString}. */
final class ClinicalEntryRequests {

    private ClinicalEntryRequests() {
    }

    record CreateAllergyRequest(
            @NotBlank @Size(max = 200) String substance,
            @Size(max = 1000) String reaction,
            @NotBlank String severity) {
        @Override
        public String toString() {
            return "CreateAllergyRequest[severity=" + severity + ", ...]";
        }
    }

    record CreateHistoryRequest(
            @NotBlank @Size(max = 200) String condition,
            @Size(max = 1000) String notes) {
        @Override
        public String toString() {
            return "CreateHistoryRequest[...]";
        }
    }

    /** Only {@code ENTERED_IN_ERROR} is a valid target; entries have no other status change. */
    record EntryStatusRequest(@NotBlank String status) {
    }
}
