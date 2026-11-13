package com.hms.organization.application;

import java.time.Instant;
import java.util.UUID;

/** A department or specialty as returned by the API. */
public record CatalogItemView(UUID id, String code, String name, boolean active, long version, Instant createdAt,
        Instant updatedAt) {
}
