package com.hms.practitioner.web;

import com.hms.practitioner.application.PractitionerCommands;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** Request bodies of /api/v1/doctors (api-design.md section 5). Value rules live in the service. */
final class PractitionerRequests {

    private PractitionerRequests() {
    }

    record Create(
            @NotNull UUID userId,
            @NotNull @Size(max = 120) String fullName,
            @NotNull @Size(max = 50) String registrationNo,
            @Size(max = 100) String registrationCouncil,
            @Size(max = 10) List<String> qualifications,
            Integer defaultSlotMinutes,
            @NotNull @Size(min = 1, max = 20) List<UUID> specialtyIds,
            @NotNull UUID primarySpecialtyId,
            @Size(max = 20) List<UUID> departmentIds) {

        PractitionerCommands.Register toCommand() {
            return new PractitionerCommands.Register(userId, fullName, registrationNo, registrationCouncil,
                    qualifications, defaultSlotMinutes, specialtyIds, primarySpecialtyId, departmentIds);
        }
    }

    record Update(
            @NotNull Long version,
            @Size(max = 120) String fullName,
            @Size(max = 50) String registrationNo,
            @Size(max = 100) String registrationCouncil,
            @Size(max = 10) List<String> qualifications,
            Integer defaultSlotMinutes,
            String status) {

        PractitionerCommands.Update toCommand() {
            return new PractitionerCommands.Update(version, fullName, registrationNo, registrationCouncil,
                    qualifications, defaultSlotMinutes, status);
        }
    }

    record Specialties(@NotNull Long version, @NotNull @Size(min = 1, max = 20) List<UUID> specialtyIds,
            @NotNull UUID primarySpecialtyId) {
    }

    record Departments(@NotNull Long version, @NotNull @Size(max = 20) List<UUID> departmentIds) {
    }
}
