package com.hms.clinical.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}, inside the finalizing transaction) when an encounter is
 * finalized ([E6-2]). No listener exists yet (verified at the M3 review); any future listener with side effects must be a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back transaction never leaks the event. Carries ids
 * only, never clinical text. {@code appointmentId} is null for an ad hoc encounter.
 */
public record EncounterFinalized(UUID organizationId, UUID encounterId, UUID patientId, UUID practitionerId,
        UUID appointmentId, Instant finalizedAt) {
}
