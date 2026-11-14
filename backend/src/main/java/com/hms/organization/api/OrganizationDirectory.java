package com.hms.organization.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Read access to organization data for other modules (hld.md section 4: identity depends on organization). */
public interface OrganizationDirectory {

    Optional<OrganizationSummary> find(UUID organizationId);

    /** The organization's patient duplicate thresholds; the defaults when the organization is unknown. */
    DuplicateThresholds duplicateThresholds(UUID organizationId);

    /**
     * Active flag per department id of {@code organizationId} among {@code ids}; unknown and other-org ids are absent.
     * The rows stay share-locked until the caller's transaction ends.
     */
    Map<UUID, Boolean> departmentActiveFlags(UUID organizationId, Collection<UUID> ids);

    /** As {@link #departmentActiveFlags} for specialties. */
    Map<UUID, Boolean> specialtyActiveFlags(UUID organizationId, Collection<UUID> ids);
}
