package com.hms.organization.api;

import java.util.UUID;

/**
 * Creates organizations, for the first-run bootstrap. Lives in organization's api so that identity (which owns the
 * roles and the first administrator) can orchestrate it without organization depending on identity.
 */
public interface OrganizationProvisioning {

    /** Whether any organization exists (the bootstrap runs only on an empty installation). */
    boolean anyOrganizationExists();

    /** Creates an active organization in the caller's transaction; the timezone must be a valid IANA id. */
    UUID create(String name, String timezone);
}
