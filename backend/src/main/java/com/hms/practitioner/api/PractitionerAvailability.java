package com.hms.practitioner.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * Availability of a practitioner for other modules ([E4-3]; hld.md section 4: appointment depends on practitioner).
 * The appointment module uses it to validate bookings (BR-A2). Free slots already leave out booked times, which
 * this module learns through the {@link BookedTimes} port. Unknown, other-organization and inactive practitioners
 * have no availability.
 */
public interface PractitionerAvailability {

    /** Free (unbooked) slots on the organization-local dates {@code from..to} (inclusive, at most 31 days). */
    List<TimeSlot> freeSlots(UUID organizationId, UUID practitionerId, LocalDate from, LocalDate to);

    /**
     * Whether {@code [start, end)} lies inside one weekly window of the practitioner, on that window's slot grid
     * (start and end a whole number of slots from the window start; owner decision 2026-11-18, Q9), and touches no
     * active leave. Says nothing about existing bookings.
     */
    boolean isAvailable(UUID organizationId, UUID practitionerId, Instant start, Instant end);

    /** Whether the practitioner exists in the organization and is ACTIVE. */
    boolean isActive(UUID organizationId, UUID practitionerId);

    /** The practitioner's default slot length in minutes; empty for an unknown or other-organization practitioner. */
    OptionalInt defaultSlotMinutes(UUID organizationId, UUID practitionerId);

    /** Default slot length of an ACTIVE practitioner; empty when unknown, in another organization, or inactive. */
    OptionalInt activeSlotMinutes(UUID organizationId, UUID practitionerId);

    /** Half-open {@code [startAt, endAt)} in UTC. */
    record TimeSlot(Instant startAt, Instant endAt) {
    }
}
