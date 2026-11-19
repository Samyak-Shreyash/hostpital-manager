package com.hms.clinical.api;

import java.util.Optional;
import java.util.UUID;

/**
 * What other modules (prescriptions, billing) may know about an encounter ([E6-1]). Scoped to the organization:
 * an unknown or other-organization id is empty. Status is {@code OPEN} or {@code FINALIZED}.
 */
public interface EncounterQuery {

    Optional<EncounterSummary> find(UUID organizationId, UUID encounterId);

    record EncounterSummary(UUID id, UUID patientId, UUID practitionerId, String status) {
    }
}
