package com.hms.practitioner.application;

import java.util.UUID;

/** Inputs of {@link StaffService}; null means "not given". Validated by the service. */
public final class StaffCommands {

    private StaffCommands() {
    }

    public record Register(UUID userId, String fullName, String staffType, String designation, UUID departmentId) {
    }

    /** Partial update; {@code designation: ""} clears it, {@code clearDepartment} removes the department. */
    public record Update(Long version, String fullName, String staffType, String designation, UUID departmentId,
            boolean clearDepartment, String status) {
    }
}
