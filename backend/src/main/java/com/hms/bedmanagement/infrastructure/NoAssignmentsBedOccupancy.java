package com.hms.bedmanagement.infrastructure;

import com.hms.bedmanagement.application.BedOccupancy;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Placeholder until bed assignments exist ([E8-2] replaces it): no bed is ever occupied. */
@Component
class NoAssignmentsBedOccupancy implements BedOccupancy {

    @Override
    public boolean isOccupied(UUID organizationId, UUID bedId) {
        return false;
    }

    @Override
    public Map<UUID, UUID> admissionsByOccupiedBed(UUID organizationId, Collection<UUID> bedIds) {
        return Map.of();
    }
}
