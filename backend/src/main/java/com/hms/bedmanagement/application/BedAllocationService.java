package com.hms.bedmanagement.application;

import com.hms.bedmanagement.api.BedAllocation;
import com.hms.bedmanagement.infrastructure.BedAssignmentRepository;
import com.hms.bedmanagement.infrastructure.BedAssignmentRepository.LockedBed;
import com.hms.bedmanagement.infrastructure.BedAssignmentRepository.OpenAssignment;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bed assignments ([E8-2]). Runs inside the caller's transaction ({@code MANDATORY}): the bed lock taken here must
 * be held until the admission and its assignment commit, and it is the same bed row lock {@link BedService} takes
 * before taking a bed out of service, so the two cannot race. Auditing is the caller's (it knows the actor).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class BedAllocationService implements BedAllocation {

    private final BedAssignmentRepository assignments;
    private final UuidV7Generator ids;

    public BedAllocationService(BedAssignmentRepository assignments, UuidV7Generator ids) {
        this.assignments = assignments;
        this.ids = ids;
    }

    @Override
    public BedRef lockAvailable(UUID organizationId, UUID bedId) {
        LockedBed bed = assignments.lockBed(organizationId, bedId)
                .orElseThrow(() -> new NotFoundException("Bed not found."));
        if (!BedService.ACTIVE.equals(bed.status()) || !BedService.ACTIVE.equals(bed.wardStatus())
                || assignments.hasOpenAssignment(organizationId, bedId)) {
            throw notAvailable();
        }
        return bed.ref();
    }

    @Override
    public void lockBeds(UUID organizationId, List<UUID> bedIds) {
        List<UUID> distinct = bedIds.stream().distinct().sorted().toList();
        if (assignments.lockBeds(organizationId, distinct) != distinct.size()) {
            throw new NotFoundException("Bed not found.");
        }
    }

    @Override
    public AssignmentView open(UUID organizationId, UUID bedId, UUID admissionId, Instant at) {
        UUID id = ids.next();
        if (!assignments.insert(id, organizationId, bedId, admissionId, at)) {
            throw notAvailable();
        }
        return assignments.find(organizationId, id).orElseThrow();
    }

    @Override
    public AssignmentView release(UUID organizationId, UUID admissionId, Instant at) {
        UUID id = assignments.release(organizationId, admissionId, at).orElseThrow(() -> new ConflictException(
                "INVALID_STATE_TRANSITION", "The admission holds no bed."));
        return assignments.find(organizationId, id).orElseThrow();
    }

    @Override
    public List<AssignmentView> history(UUID organizationId, UUID admissionId) {
        return assignments.history(organizationId, admissionId);
    }

    @Override
    public Map<UUID, AssignmentView> current(UUID organizationId, Collection<UUID> admissionIds) {
        Map<UUID, AssignmentView> result = new HashMap<>();
        for (OpenAssignment open : assignments.openForAdmissions(organizationId, List.copyOf(admissionIds))) {
            result.put(open.admissionId(), open.view());
        }
        return result;
    }

    @Override
    public List<UUID> admissionsInWard(UUID organizationId, UUID wardId) {
        return assignments.admissionsInWard(organizationId, wardId);
    }

    private static ConflictException notAvailable() {
        return new ConflictException("BED_NOT_AVAILABLE", "The bed is occupied or not in service.");
    }
}
