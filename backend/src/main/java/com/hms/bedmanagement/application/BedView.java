package com.hms.bedmanagement.application;

import java.time.Instant;
import java.util.UUID;

public record BedView(UUID id, UUID wardId, String bedNo, String bedClass, UUID dailyRateServiceId, String status,
        long version, Instant createdAt, Instant updatedAt) {
}
