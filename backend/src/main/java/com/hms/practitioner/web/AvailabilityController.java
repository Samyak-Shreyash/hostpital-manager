package com.hms.practitioner.web;

import com.hms.practitioner.application.AvailabilityService;
import com.hms.practitioner.application.AvailabilityService.AvailabilityView;
import com.hms.practitioner.application.AvailabilityService.ExceptionView;
import com.hms.practitioner.application.AvailabilityService.SlotsView;
import com.hms.practitioner.application.AvailabilityService.WindowInput;
import com.hms.shared.tenancy.OrganizationPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Doctor availability ([E4-3], api-design.md section 5): weekly template, leave exceptions and free slots.
 * Free slots need {@code appointment:read} (front desk books from them); the template and leave need
 * {@code practitioner:schedule} to change and {@code practitioner:read} to view.
 */
@RestController
@RequestMapping("/api/v1/doctors/{id}")
class AvailabilityController {

    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    record Window(Integer weekday, String start, String end, Integer slotMinutes) {
    }

    record ReplaceAvailability(@NotNull Long version, @NotNull @Size(max = 50) List<Window> windows) {
    }

    record CreateException(@NotNull LocalDate date, @Size(max = 5) String start, @Size(max = 5) String end) {
    }

    record Cancel(@NotNull Long version) {
    }

    @GetMapping("/availability")
    @PreAuthorize("hasAuthority('practitioner:read')")
    AvailabilityView get(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id) {
        return availability.get(actor, id);
    }

    @PutMapping("/availability")
    @PreAuthorize("hasAuthority('practitioner:schedule')")
    AvailabilityView replace(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody ReplaceAvailability request) {
        List<WindowInput> windows = request.windows().stream()
                .map(w -> w == null ? null : new WindowInput(w.weekday(), w.start(), w.end(), w.slotMinutes()))
                .toList();
        return availability.replace(actor, id, request.version(), windows);
    }

    @PostMapping("/availability-exceptions")
    @PreAuthorize("hasAuthority('practitioner:schedule')")
    @ResponseStatus(HttpStatus.CREATED)
    ExceptionView createException(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody CreateException request) {
        return availability.createException(actor, id, request.date(), request.start(), request.end());
    }

    @GetMapping("/availability-exceptions")
    @PreAuthorize("hasAuthority('practitioner:read')")
    List<ExceptionView> exceptions(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return availability.exceptions(actor, id, from, to);
    }

    @PostMapping("/availability-exceptions/{exceptionId}/cancel")
    @PreAuthorize("hasAuthority('practitioner:schedule')")
    ExceptionView cancelException(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @PathVariable UUID exceptionId, @Valid @RequestBody Cancel request) {
        return availability.cancelException(actor, id, exceptionId, request.version());
    }

    @GetMapping("/slots")
    @PreAuthorize("hasAuthority('appointment:read')")
    SlotsView slots(@AuthenticationPrincipal OrganizationPrincipal actor, @PathVariable UUID id,
            @RequestParam(required = false) String date, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return availability.slots(actor, id, date, from, to);
    }
}
