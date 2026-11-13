package com.hms.practitioner.application;

import java.util.List;
import java.util.UUID;

/** Inputs of {@link PractitionerService}; null means "not given". Validated by the service. */
public final class PractitionerCommands {

    private PractitionerCommands() {
    }

    public record Register(UUID userId, String fullName, String registrationNo, String registrationCouncil,
            List<String> qualifications, Integer defaultSlotMinutes, List<UUID> specialtyIds,
            UUID primarySpecialtyId, List<UUID> departmentIds) {
    }

    /** Partial update; {@code registrationCouncil: ""} clears it. */
    public record Update(Long version, String fullName, String registrationNo, String registrationCouncil,
            List<String> qualifications, Integer defaultSlotMinutes, String status) {
    }

    public record ReplaceSpecialties(Long version, List<UUID> specialtyIds, UUID primarySpecialtyId) {
    }

    public record ReplaceDepartments(Long version, List<UUID> departmentIds) {
    }
}
