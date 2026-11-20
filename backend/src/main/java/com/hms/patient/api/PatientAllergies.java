package com.hms.patient.api;

import java.util.List;
import java.util.UUID;

/**
 * Read-only port to a patient's active allergies, for the prescription allergy warning ([E6-3]). Entries marked
 * entered in error are not returned; an unknown patient or one of another organization yields an empty list.
 */
public interface PatientAllergies {

    List<AllergyInfo> activeAllergies(UUID organizationId, UUID patientId);

    /** Clinical free text (substance): never logged or audited by callers. */
    record AllergyInfo(String substance, String severity) {
    }
}
