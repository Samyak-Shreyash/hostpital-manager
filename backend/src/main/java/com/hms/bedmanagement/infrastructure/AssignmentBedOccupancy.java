package com.hms.bedmanagement.infrastructure;

import com.hms.bedmanagement.application.BedOccupancy;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Occupancy from open bed assignments ([E8-2]): a bed is occupied while it has an assignment without release. */
@Component
class AssignmentBedOccupancy implements BedOccupancy {

    private final BedAssignmentRepository assignments;

    AssignmentBedOccupancy(BedAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    @Override
    public boolean isOccupied(UUID organizationId, UUID bedId) {
        return assignments.hasOpenAssignment(organizationId, bedId);
    }

    @Override
    public Map<UUID, UUID> admissionsByOccupiedBed(UUID organizationId, Collection<UUID> bedIds) {
        Map<UUID, UUID> result = new HashMap<>();
        for (UUID[] pair : assignments.openForBeds(organizationId, List.copyOf(bedIds))) {
            result.put(pair[0], pair[1]);
        }
        return result;
    }
}
