package com.hms.practitioner.web;

import com.hms.practitioner.application.StaffCommands;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Request bodies of /api/v1/staff. Value rules live in the service. */
final class StaffRequests {

    private StaffRequests() {
    }

    record Create(@NotNull UUID userId, @NotNull @Size(max = 120) String fullName, @NotNull String staffType,
            @Size(max = 80) String designation, UUID departmentId) {

        StaffCommands.Register toCommand() {
            return new StaffCommands.Register(userId, fullName, staffType, designation, departmentId);
        }
    }

    record Update(@NotNull Long version, @Size(max = 120) String fullName, String staffType,
            @Size(max = 80) String designation, UUID departmentId, Boolean clearDepartment, String status) {

        StaffCommands.Update toCommand() {
            return new StaffCommands.Update(version, fullName, staffType, designation, departmentId,
                    Boolean.TRUE.equals(clearDepartment), status);
        }
    }
}
