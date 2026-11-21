package com.hms.bedmanagement.application;

import java.time.Instant;
import java.util.UUID;

public record WardView(UUID id, String code, String name, UUID departmentId, String status, long version,
        Instant createdAt, Instant updatedAt) {
}
