package com.hms.organization.api;

import java.util.Optional;
import java.util.UUID;

/** Read access to organization data for other modules (hld.md section 4: identity depends on organization). */
public interface OrganizationDirectory {

    Optional<OrganizationSummary> find(UUID organizationId);

    /** The organization's patient duplicate thresholds; the defaults when the organization is unknown. */
    DuplicateThresholds duplicateThresholds(UUID organizationId);
}
