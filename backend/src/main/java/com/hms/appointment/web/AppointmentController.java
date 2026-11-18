package com.hms.appointment.web;

import com.hms.appointment.application.AppointmentService;
import com.hms.appointment.application.AppointmentService.BookCommand;
import com.hms.appointment.application.AppointmentView;
import com.hms.identity.api.Permissions;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Appointments (api-design.md section 7). [E5-1]: book and read; the state machine follows in [E5-2]. */
@RestController
@RequestMapping("/api/v1/appointments")
class AppointmentController {

    private final AppointmentService appointments;

    AppointmentController(AppointmentService appointments) {
        this.appointments = appointments;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('appointment:create')")
    @ResponseStatus(HttpStatus.CREATED)
    AppointmentView book(@AuthenticationPrincipal OrganizationPrincipal actor, Authentication authentication,
            @Valid @RequestBody BookAppointmentRequest request) {
        return appointments.book(actor, new BookCommand(request.patientId(), request.doctorId(), request.startAt(),
                request.durationMinutes(), request.reason(), Boolean.TRUE.equals(request.outsideAvailability()),
                has(authentication, Permissions.APPOINTMENT_BACKDATE),
                has(authentication, Permissions.APPOINTMENT_OVERRIDE)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('appointment:read')")
    AppointmentView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return appointments.get(actor, id);
    }

    private static boolean has(Authentication authentication, String permission) {
        return authentication.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }
}
