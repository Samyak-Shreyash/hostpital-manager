package com.hms.bedmanagement.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bed assignments for the admission module ([E8-2]; hld.md section 4: admission depends on bedmanagement). Every
 * method runs in the caller's transaction and is scoped to the organization; an unknown or other-organization bed
 * is {@code 404}. Bed rows are the first lock of the M4 lock order (bed, admission, invoice, document_sequence), so
 * callers lock beds before they lock the admission row.
 */
public interface BedAllocation {

    /**
     * Locks the bed row ({@code SELECT ... FOR UPDATE}, the same lock bed administration takes) and checks it can be
     * assigned: {@code 409 BED_NOT_AVAILABLE} when the bed is OUT_OF_SERVICE, its ward is INACTIVE or it has an
     * open assignment.
     */
    BedRef lockAvailable(UUID organizationId, UUID bedId);

    /** Locks the given beds in ascending id order (deadlock-free for two-bed moves); any unknown bed is 404. */
    void lockBeds(UUID organizationId, List<UUID> bedIds);

    /** Opens an assignment; the caller holds the bed lock. A second open assignment of the bed is 409. */
    default AssignmentView open(UUID organizationId, UUID bedId, UUID admissionId, Instant at) {
        return open(organizationId, bedId, admissionId, at, null);
    }

    /** As {@link #open(UUID, UUID, UUID, Instant)}, recording why (a transfer); {@code reason} may be null. */
    AssignmentView open(UUID organizationId, UUID bedId, UUID admissionId, Instant at, String reason);

    /** Releases the open assignment of the admission at {@code at}; {@code 409} when there is none. */
    AssignmentView release(UUID organizationId, UUID admissionId, Instant at);

    /** Every assignment of the admission, oldest first. */
    List<AssignmentView> history(UUID organizationId, UUID admissionId);

    /** The open assignment of each admission that has one; others are absent. */
    Map<UUID, AssignmentView> current(UUID organizationId, Collection<UUID> admissionIds);

    /** Admissions that currently hold a bed of the ward. */
    List<UUID> admissionsInWard(UUID organizationId, UUID wardId);

    record BedRef(UUID id, String bedNo, UUID wardId, String wardName, UUID dailyRateServiceId) {
    }

    record AssignmentView(UUID id, UUID bedId, String bedNo, String wardName, UUID dailyRateServiceId,
            Instant assignedAt, Instant releasedAt, String reason) {
    }
}
