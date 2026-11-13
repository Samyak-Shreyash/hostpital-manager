package com.hms.identity.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Read access to users for other modules (hld.md section 4: practitioner depends on identity). Lookups are always
 * scoped to the given organization: a user of another organization is indistinguishable from an unknown id.
 */
public interface UserDirectory {

    /** What another module may know about a user. */
    record UserSummary(UUID id, String fullName, boolean active) {
    }

    /**
     * The user of {@code organizationId} with this id, share-locked until the caller's transaction ends (so it cannot
     * be deactivated while being linked); empty for an unknown or other-org id.
     */
    Optional<UserSummary> findForLink(UUID organizationId, UUID userId);
}
