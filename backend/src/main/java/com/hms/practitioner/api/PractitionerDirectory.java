package com.hms.practitioner.api;

import java.util.Optional;
import java.util.UUID;

/**
 * The practitioner-to-user link ([E4-2]) for other modules: the clinical module decides who the treating
 * practitioner of an encounter is from the signed-in user. Scoped to the organization.
 */
public interface PractitionerDirectory {

    /** The ACTIVE practitioner record linked to this user; empty when the user is no practitioner. */
    Optional<UUID> activePractitionerOfUser(UUID organizationId, UUID userId);

    /** Name and registration of a practitioner of the organization (any status), for printed documents. */
    Optional<PractitionerBrief> brief(UUID organizationId, UUID practitionerId);

    /** Public professional identity only: name, registration number and council (may be null). */
    record PractitionerBrief(String fullName, String registrationNo, String registrationCouncil) {
    }
}
