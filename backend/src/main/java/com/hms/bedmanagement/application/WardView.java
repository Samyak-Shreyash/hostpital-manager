package com.hms.bedmanagement.application;

import java.time.Instant;
import java.util.UUID;

public record WardView(UUID id, String code, String name, UUID departmentId, String status, String careType, long version,
        Instant createdAt, Instant updatedAt) {
}
