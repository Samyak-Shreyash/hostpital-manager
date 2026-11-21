package com.hms.admission.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published (Spring {@code ApplicationEventPublisher}, inside the discharge transaction) when an admission is
 * discharged ([E8-7]). No listener exists yet; any future listener with side effects must be a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} so a rolled-back discharge never leaks the event. Ids only.
 */
public record PatientDischarged(UUID organizationId, UUID admissionId, UUID patientId, Instant dischargedAt) {
}
