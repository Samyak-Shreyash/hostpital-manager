package com.hms.appointment.application;

import com.hms.appointment.infrastructure.AppointmentRepository;
import com.hms.appointment.infrastructure.AppointmentRepository.AppointmentRow;
import com.hms.appointment.infrastructure.AppointmentRepository.NewAppointment;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking ([E5-1], APT-1, BR-A1, BR-A2; api-design.md section 7).
 *
 * <p>No overlap (BR-A1) is enforced by the V23 exclusion constraint; bookings of one practitioner are serialized by
 * an advisory lock first, so concurrent requests answer one {@code 201} and the rest {@code 409 SLOT_CONFLICT}.
 * The time must lie in the practitioner's availability, on its slot grid and clear of leave (BR-A2, Q9), unless the
 * caller asks for {@code outsideAvailability} and holds {@code appointment:override}; such a booking is flagged and
 * audited. A start in the past needs {@code appointment:backdate}. {@code reason} is free text: its value is never
 * audited or logged.
 */
@Service
public class AppointmentService {

    static final String ENTITY = "APPOINTMENT";
    static final int MAX_DURATION_MINUTES = 480;
    static final int MAX_REASON_LENGTH = 500;

    /** The caller's booking request. {@code mayBackdate}/{@code mayOverride} come from the caller's permissions. */
    public record BookCommand(UUID patientId, UUID doctorId, Instant startAt, Integer durationMinutes, String reason,
            boolean outsideAvailability, boolean mayBackdate, boolean mayOverride) {
    }

    private final AppointmentRepository repository;
    private final PractitionerAvailability availability;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AppointmentService(AppointmentRepository repository, PractitionerAvailability availability,
            AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.availability = availability;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public AppointmentView book(OrganizationPrincipal actor, BookCommand cmd) {
        UUID org = actor.organizationId();
        if (cmd.patientId() == null || cmd.doctorId() == null || cmd.startAt() == null
                || cmd.durationMinutes() == null) {
            throw invalid("patientId, doctorId, startAt and durationMinutes are required.");
        }
        if (cmd.durationMinutes() < 1 || cmd.durationMinutes() > MAX_DURATION_MINUTES) {
            throw invalid("durationMinutes must be 1 to " + MAX_DURATION_MINUTES + ".");
        }
        if (cmd.startAt().getEpochSecond() % 60 != 0 || cmd.startAt().getNano() != 0) {
            throw invalid("startAt must be a whole minute.");
        }
        String reason = cmd.reason() == null ? null : cmd.reason().strip();
        if (reason != null && reason.isEmpty()) {
            reason = null;
        }
        if (reason != null && reason.length() > MAX_REASON_LENGTH) {
            throw invalid("reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        if (cmd.outsideAvailability() && !cmd.mayOverride()) {
            throw new AccessDeniedException("Booking outside availability needs appointment:override.");
        }
        Instant start = cmd.startAt();
        Instant end = start.plus(Duration.ofMinutes(cmd.durationMinutes()));
        Instant now = clock.instant();
        if (start.isBefore(now) && !cmd.mayBackdate()) {
            throw new BusinessRuleException("START_IN_PAST",
                    "startAt is in the past; booking a past time needs appointment:backdate.");
        }
        if (!availability.isActive(org, cmd.doctorId())) {
            throw new BusinessRuleException("DOCTOR_NOT_AVAILABLE", "The doctor does not exist or is inactive.");
        }
        if (!cmd.outsideAvailability() && !availability.isAvailable(org, cmd.doctorId(), start, end)) {
            throw new BusinessRuleException("OUTSIDE_AVAILABILITY",
                    "The time is not a free slot of the doctor's availability (working hours, slot grid, leave).");
        }

        repository.lockSchedule(cmd.doctorId());
        UUID id = ids.next();
        switch (repository.insert(new NewAppointment(id, org, cmd.patientId(), cmd.doctorId(), start, end, reason,
                cmd.outsideAvailability(), actor.userId(), now))) {
            case OVERLAP -> throw new ConflictException("SLOT_CONFLICT",
                    "The doctor already has an appointment at this time.");
            case UNKNOWN_PATIENT -> throw new BusinessRuleException("PATIENT_NOT_AVAILABLE",
                    "The patient does not exist.");
            case INSERTED -> { }
        }
        List<String> fields = new ArrayList<>(List.of("patientId", "doctorId", "startAt", "endAt"));
        if (reason != null) {
            fields.add("reason");
        }
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_BOOKED", ENTITY, id, AuditOutcome.SUCCESS,
                fields));
        if (cmd.outsideAvailability()) {
            audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_AVAILABILITY_OVERRIDDEN", ENTITY, id,
                    AuditOutcome.SUCCESS, List.of("outsideAvailability")));
        }
        return view(repository.find(org, id).orElseThrow());
    }

    /** Reading an appointment is audited: it ties a patient to a doctor and a visit reason. */
    @Transactional
    public AppointmentView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        AppointmentRow row = repository.find(org, id)
                .orElseThrow(() -> new NotFoundException("Appointment not found."));
        audit.record(AuditEntry.of(org, actor.userId(), "APPOINTMENT_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return view(row);
    }

    private static AppointmentView view(AppointmentRow r) {
        return new AppointmentView(r.id(), r.patientId(), r.practitionerId(), r.startAt(), r.endAt(),
                (int) Duration.between(r.startAt(), r.endAt()).toMinutes(), r.type(), r.status(), r.reason(),
                r.outsideAvailability(), r.version(), r.createdAt(), r.updatedAt());
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
