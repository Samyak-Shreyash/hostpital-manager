package com.hms.appointment.application;

import com.hms.appointment.api.AppointmentLifecycle;
import com.hms.appointment.api.AppointmentLookup;
import com.hms.appointment.domain.AppointmentStatus;
import com.hms.appointment.infrastructure.AppointmentRepository;
import com.hms.appointment.infrastructure.AppointmentRepository.AppointmentRow;
import com.hms.appointment.infrastructure.AppointmentRepository.ListFilter;
import com.hms.appointment.infrastructure.AppointmentRepository.NewAppointment;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.organization.api.OrganizationSummary;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking ([E5-1], APT-1, BR-A1, BR-A2) and the state machine ([E5-2]; api-design.md section 7).
 *
 * <p>No overlap (BR-A1) is enforced by the V23 exclusion constraint; bookings of one practitioner are serialized by
 * an advisory lock first, so concurrent requests answer one {@code 201} and the rest {@code 409 SLOT_CONFLICT}.
 * The time must lie in the practitioner's availability, on its slot grid and clear of leave (BR-A2, Q9), unless the
 * caller asks for {@code outsideAvailability} and holds {@code appointment:override}; such a booking is flagged and
 * audited. A start in the past needs {@code appointment:backdate}. {@code reason} and the cancel reason are free
 * text: their values are never audited or logged.
 *
 * <p>State changes lock the row, compare the caller's {@code version} (stale: {@code 409 CONCURRENT_MODIFICATION})
 * and then ask {@link AppointmentStatus#canTransitionTo} ({@code 409 INVALID_STATE_TRANSITION}).
 */
@Service
public class AppointmentService implements AppointmentLifecycle, AppointmentLookup {

    static final String ENTITY = "APPOINTMENT";
    static final int MAX_DURATION_MINUTES = 480;
    static final int MAX_REASON_LENGTH = 500;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Arrays.stream(AppointmentStatus.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    /** The caller's booking request. {@code mayBackdate}/{@code mayOverride} come from the caller's permissions. */
    public record BookCommand(UUID patientId, UUID doctorId, Instant startAt, Integer durationMinutes, String reason,
            boolean outsideAvailability, boolean mayBackdate, boolean mayOverride) {
    }

    private final AppointmentRepository repository;
    private final PractitionerAvailability availability;
    private final OrganizationDirectory organizations;
    private final PatientDirectory patients;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AppointmentService(AppointmentRepository repository, PractitionerAvailability availability,
            OrganizationDirectory organizations, PatientDirectory patients, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.availability = availability;
        this.organizations = organizations;
        this.patients = patients;
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
        insertOrFail(new NewAppointment(id, org, cmd.patientId(), cmd.doctorId(), start, end, reason,
                cmd.outsideAvailability(), actor.userId(), now, null));
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
        return withBrief(patients, org, view(repository.find(org, id).orElseThrow()));
    }

    /**
     * Moves an appointment to a new time ([E5-2]): the old one becomes RESCHEDULED (freeing its time, so the new
     * slot may overlap it) and a new SCHEDULED one, linked by {@code rescheduledFromId}, is booked in the same
     * transaction under the booking rules; any failure rolls both back. A past start needs {@code mayBackdate};
     * there is no availability override on a reschedule.
     */
    @Transactional
    public AppointmentView reschedule(OrganizationPrincipal actor, UUID id, Long version, Instant startAt,
            Integer durationMinutes, boolean mayBackdate) {
        UUID org = actor.organizationId();
        if (version == null || startAt == null || durationMinutes == null) {
            throw invalid("version, startAt and durationMinutes are required.");
        }
        if (durationMinutes < 1 || durationMinutes > MAX_DURATION_MINUTES) {
            throw invalid("durationMinutes must be 1 to " + MAX_DURATION_MINUTES + ".");
        }
        if (startAt.getEpochSecond() % 60 != 0 || startAt.getNano() != 0) {
            throw invalid("startAt must be a whole minute.");
        }
        AppointmentRow old = repository.lock(org, id).orElseThrow(AppointmentService::notFound);
        requireTransition(old, version, AppointmentStatus.RESCHEDULED);
        Instant end = startAt.plus(Duration.ofMinutes(durationMinutes));
        Instant now = clock.instant();
        if (startAt.isBefore(now) && !mayBackdate) {
            throw new BusinessRuleException("START_IN_PAST",
                    "startAt is in the past; a past time needs appointment:backdate.");
        }
        if (!availability.isActive(org, old.practitionerId())) {
            throw new BusinessRuleException("DOCTOR_NOT_AVAILABLE", "The doctor does not exist or is inactive.");
        }
        if (!availability.isAvailable(org, old.practitionerId(), startAt, end)) {
            throw new BusinessRuleException("OUTSIDE_AVAILABILITY",
                    "The time is not a free slot of the doctor's availability (working hours, slot grid, leave).");
        }
        repository.lockSchedule(old.practitionerId());
        repository.transition(org, id, AppointmentStatus.RESCHEDULED.name(), null, null, now);
        UUID fresh = ids.next();
        insertOrFail(new NewAppointment(fresh, org, old.patientId(), old.practitionerId(), startAt, end,
                old.reason(), false, actor.userId(), now, id));
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_RESCHEDULED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("status")));
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_BOOKED", ENTITY, fresh, AuditOutcome.SUCCESS,
                List.of("patientId", "doctorId", "startAt", "endAt", "rescheduledFromId")));
        return withBrief(patients, org, view(repository.find(org, fresh).orElseThrow()));
    }

    /** SCHEDULED or CHECKED_IN to CANCELLED; the reason is required and never audited or logged. */
    @Transactional
    public AppointmentView cancel(OrganizationPrincipal actor, UUID id, Long version, String reason) {
        UUID org = actor.organizationId();
        if (version == null) {
            throw invalid("version is required.");
        }
        String text = reason == null ? "" : reason.strip();
        if (text.isEmpty()) {
            throw invalid("reason is required.");
        }
        if (text.length() > MAX_REASON_LENGTH) {
            throw invalid("reason must be at most " + MAX_REASON_LENGTH + " characters.");
        }
        AppointmentRow row = repository.lock(org, id).orElseThrow(AppointmentService::notFound);
        requireTransition(row, version, AppointmentStatus.CANCELLED);
        Instant now = clock.instant();
        repository.transition(org, id, AppointmentStatus.CANCELLED.name(), text, now, now);
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_CANCELLED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status", "cancelReason")));
        return withBrief(patients, org, view(repository.find(org, id).orElseThrow()));
    }

    /** SCHEDULED to NO_SHOW: frees the time. */
    @Transactional
    public AppointmentView noShow(OrganizationPrincipal actor, UUID id, Long version) {
        UUID org = actor.organizationId();
        if (version == null) {
            throw invalid("version is required.");
        }
        AppointmentRow row = repository.lock(org, id).orElseThrow(AppointmentService::notFound);
        requireTransition(row, version, AppointmentStatus.NO_SHOW);
        repository.transition(org, id, AppointmentStatus.NO_SHOW.name(), null, null, clock.instant());
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_NO_SHOW", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status")));
        return withBrief(patients, org, view(repository.find(org, id).orElseThrow()));
    }

    /**
     * Appointments of the organization by start time. {@code date} is a day in the organization's timezone.
     * Listing ties patients to doctors, so it is audited like a read (names of the filters used only).
     */
    @Transactional
    public PageResult<AppointmentView> list(OrganizationPrincipal actor, UUID doctorId, UUID patientId,
            LocalDate date, String status, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status must be one of " + STATUSES + ".");
        }
        UUID org = actor.organizationId();
        Instant from = null;
        Instant to = null;
        if (date != null) {
            ZoneId zone = ZoneId.of(organizations.find(org).map(OrganizationSummary::timezone)
                    .orElseThrow(() -> new IllegalStateException("organization not found")));
            from = date.atStartOfDay(zone).toInstant();
            to = date.plusDays(1).atStartOfDay(zone).toInstant();
        }
        ListFilter filter = new ListFilter(doctorId, patientId, from, to, status);
        List<AppointmentView> items = withBriefs(patients, org, repository.list(org, filter, size, (long) page * size)
                .stream().map(AppointmentService::view).toList());
        List<String> used = new ArrayList<>();
        if (doctorId != null) {
            used.add("doctorId");
        }
        if (patientId != null) {
            used.add("patientId");
        }
        if (date != null) {
            used.add("date");
        }
        if (status != null) {
            used.add("status");
        }
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_LISTED", ENTITY, null, AuditOutcome.SUCCESS,
                used));
        return new PageResult<>(items, page, size, repository.count(org, filter));
    }

    /** Reading an appointment is audited: it ties a patient to a doctor and a visit reason. */
    @Transactional
    public AppointmentView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        AppointmentRow row = repository.find(org, id).orElseThrow(AppointmentService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "APPOINTMENT_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return withBrief(patients, org, view(row));
    }

    private void insertOrFail(NewAppointment appointment) {
        switch (repository.insert(appointment)) {
            case OVERLAP -> throw new ConflictException("SLOT_CONFLICT",
                    "The doctor already has an appointment at this time.");
            case UNKNOWN_PATIENT -> throw new BusinessRuleException("PATIENT_NOT_AVAILABLE",
                    "The patient does not exist.");
            case INSERTED -> { }
        }
    }

    /**
     * The consultation starts ([E5-3] port for the clinical module): CHECKED_IN to IN_CONSULTATION, decided on the
     * locked row by the same state machine. There is no caller-supplied version here and no audit event: the
     * clinical module audits the encounter that drives it.
     */
    @Override
    @Transactional
    public void markInConsultation(UUID organizationId, UUID appointmentId) {
        move(organizationId, appointmentId, AppointmentStatus.IN_CONSULTATION);
    }

    /** The consultation ends: IN_CONSULTATION to COMPLETED. */
    @Override
    @Transactional
    public void markCompleted(UUID organizationId, UUID appointmentId) {
        move(organizationId, appointmentId, AppointmentStatus.COMPLETED);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppointmentRef> find(UUID organizationId, UUID appointmentId) {
        return repository.find(organizationId, appointmentId)
                .map(r -> new AppointmentRef(r.id(), r.patientId(), r.practitionerId(), r.status()));
    }

    private void move(UUID org, UUID id, AppointmentStatus target) {
        AppointmentRow row = repository.lock(org, id).orElseThrow(AppointmentService::notFound);
        requireState(row, target);
        repository.transition(org, id, target.name(), null, null, clock.instant());
    }

    /** Version first (a stale caller is told to reload), then the state machine on the locked row. */
    static void requireTransition(AppointmentRow row, long version, AppointmentStatus target) {
        if (row.version() != version) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
        requireState(row, target);
    }

    private static void requireState(AppointmentRow row, AppointmentStatus target) {
        AppointmentStatus current = AppointmentStatus.valueOf(row.status());
        if (!current.canTransitionTo(target)) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "An appointment that is " + current + " cannot become " + target + ".");
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("Appointment not found.");
    }

    /** Adds the patient's UHID and name to the views with one batch lookup (never a per-patient read audit). */
    static List<AppointmentView> withBriefs(PatientDirectory directory, UUID org, List<AppointmentView> views) {
        Map<UUID, PatientBrief> briefs = directory.briefs(org, views.stream().map(AppointmentView::patientId)
                .distinct().toList());
        return views.stream().map(v -> v.withPatient(briefs.getOrDefault(v.patientId(),
                new PatientBrief(v.patientId(), null, null)))).toList();
    }

    static AppointmentView withBrief(PatientDirectory directory, UUID org, AppointmentView view) {
        return withBriefs(directory, org, List.of(view)).get(0);
    }

    static AppointmentView view(AppointmentRow r) {
        return new AppointmentView(r.id(), r.patientId(), r.practitionerId(), r.startAt(), r.endAt(),
                (int) Duration.between(r.startAt(), r.endAt()).toMinutes(), r.type(), r.status(), r.reason(),
                r.outsideAvailability(), r.version(), r.createdAt(), r.updatedAt(), r.rescheduledFromId(),
                r.cancelReason(), r.cancelledAt(), r.tokenNo(), r.tokenDate(), null);
    }

    static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
