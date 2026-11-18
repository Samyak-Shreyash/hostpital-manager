package com.hms.practitioner.api;

import com.hms.practitioner.api.PractitionerAvailability.TimeSlot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Port through which the practitioner module learns which times are already booked, so free slots leave them out
 * (owner decision 2026-11-18, Q9). Implemented by the appointment module; the dependency stays appointment ->
 * practitioner (hld.md section 4), the same inversion as billing's {@code ChargeSource}.
 */
public interface BookedTimes {

    /** Booked intervals of the practitioner that overlap {@code [from, to)}, in any order. */
    List<TimeSlot> booked(UUID organizationId, UUID practitionerId, Instant from, Instant to);
}
