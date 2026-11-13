package com.hms.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A user as user management returns it. Never carries the password hash. */
public record UserView(UUID id, String email, String fullName, String status, boolean mustChangePassword,
        List<String> roleCodes, long version, Instant createdAt, Instant updatedAt) {
}
