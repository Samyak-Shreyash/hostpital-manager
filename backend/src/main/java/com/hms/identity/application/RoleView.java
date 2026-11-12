package com.hms.identity.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RoleView(UUID id, String code, String name, List<String> permissions, long version,
        Instant createdAt, Instant updatedAt) {
}
