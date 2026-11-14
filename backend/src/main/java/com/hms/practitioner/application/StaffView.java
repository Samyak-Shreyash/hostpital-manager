package com.hms.practitioner.application;

import java.time.Instant;
import java.util.UUID;

/**
 * A staff record as the API returns it. Roles are not part of it: they belong to the linked user and are managed
 * through {@code PATCH /api/v1/users/{userId}} ([E1-7], role:assign).
 */
public record StaffView(UUID id, UUID userId, String fullName, String staffType, String designation,
        UUID departmentId, String status, long version, Instant createdAt, Instant updatedAt) {
}
