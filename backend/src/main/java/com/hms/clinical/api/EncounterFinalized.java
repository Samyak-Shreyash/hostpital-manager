package com.hms.clinical.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}, inside the finalizing transaction) when an encounter is
 * finalized ([E6-2]). Listeners that must run only after commit use {@code @TransactionalEventListener}. Carries ids
 * only, never clinical text. {@code appointmentId} is null for an ad hoc encounter.
 */
public record EncounterFinalized(UUID organizationId, UUID encounterId, UUID patientId, UUID practitionerId,
        UUID appointmentId, Instant finalizedAt) {
}
