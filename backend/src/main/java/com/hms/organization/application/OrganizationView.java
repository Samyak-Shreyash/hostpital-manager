package com.hms.organization.application;

import java.time.Instant;
import java.util.UUID;

public record OrganizationView(UUID id, String name, String timezone, String gstin, String address, long version,
        Instant createdAt, Instant updatedAt) {
}
