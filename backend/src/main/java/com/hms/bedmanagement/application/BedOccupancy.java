package com.hms.bedmanagement.application;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Seam between bed administration and bed assignments (bedmanagement-internal). Implemented over open
 * {@code bed_assignment} rows ([E8-2]).
 */
public interface BedOccupancy {

    boolean isOccupied(UUID organizationId, UUID bedId);

    /** Occupied beds among {@code bedIds} mapped to the admission that holds them; free beds are absent. */
    Map<UUID, UUID> admissionsByOccupiedBed(UUID organizationId, Collection<UUID> bedIds);
}
