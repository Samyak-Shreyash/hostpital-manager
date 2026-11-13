package com.hms.patient.application;

import java.time.Instant;
import java.util.UUID;

/** Allergy and medical-history entries as returned by the API. Free text is never printed by {@code toString}. */
public final class ClinicalEntryViews {

    private ClinicalEntryViews() {
    }

    public record AllergyView(UUID id, String substance, String reaction, String severity, String status,
            Instant recordedAt, Instant enteredInErrorAt) {
        @Override
        public String toString() {
            return "AllergyView[id=" + id + ", status=" + status + "]";
        }
    }

    public record HistoryView(UUID id, String condition, String notes, String status, Instant recordedAt,
            Instant enteredInErrorAt) {
        @Override
        public String toString() {
            return "HistoryView[id=" + id + ", status=" + status + "]";
        }
    }
}
