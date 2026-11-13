package com.hms.practitioner.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A doctor as the API returns it (api-design.md section 5). {@code specialtyIds} includes the primary. */
public record PractitionerView(UUID id, UUID userId, String fullName, String registrationNo,
        String registrationCouncil, List<String> qualifications, int defaultSlotMinutes, String status,
        UUID primarySpecialtyId, List<UUID> specialtyIds, List<UUID> departmentIds, long version, Instant createdAt,
        Instant updatedAt) {
}
