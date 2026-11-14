package com.hms.practitioner.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Availability of a practitioner for other modules ([E4-3]; hld.md section 4: appointment depends on practitioner).
 * The appointment module ([E5-*]) will use it to validate bookings (BR-A2) and to list free slots; it must also
 * remove its own booked appointments from {@link #freeSlots}, since this module knows nothing about bookings.
 * Unknown, other-organization and inactive practitioners have no availability.
 */
public interface PractitionerAvailability {

    /** Free slots on the organization-local dates {@code from..to} (inclusive, at most 31 days). */
    List<TimeSlot> freeSlots(UUID organizationId, UUID practitionerId, LocalDate from, LocalDate to);

    /** Whether {@code [start, end)} lies inside one weekly window of the practitioner and touches no active leave. */
    boolean isAvailable(UUID organizationId, UUID practitionerId, Instant start, Instant end);

    /** Half-open {@code [startAt, endAt)} in UTC. */
    record TimeSlot(Instant startAt, Instant endAt) {
    }
}
