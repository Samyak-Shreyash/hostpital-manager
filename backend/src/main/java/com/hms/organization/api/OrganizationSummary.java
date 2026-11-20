package com.hms.organization.api;

import java.util.UUID;

/** What other modules may know about an organization: enough to describe the tenant, e.g. in the login
 * response or on a printed document ({@code address} may be null). */
public record OrganizationSummary(UUID id, String name, String timezone, String address) {
}
