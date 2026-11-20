package com.hms.clinical.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A prescription with its items and the allergy warnings computed against the patient's active allergies. */
public record PrescriptionView(UUID id, UUID encounterId, UUID patientId, String status, int versionNo,
        UUID supersedesId, Instant issuedAt, UUID issuedBy, long version, List<Item> items,
        List<AllergyWarning> allergyWarnings) {

    public record Item(String drugName, String strength, String form, String route, String dose, String frequency,
            int durationDays, String instructions) {
    }

    public record AllergyWarning(String substance, String severity) {
    }
}
