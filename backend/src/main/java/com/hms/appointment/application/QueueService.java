package com.hms.appointment.application;

import com.hms.appointment.domain.AppointmentStatus;
import com.hms.appointment.infrastructure.AppointmentRepository;
import com.hms.appointment.infrastructure.AppointmentRepository.AppointmentRow;
import com.hms.appointment.infrastructure.AppointmentRepository.NewWalkIn;
import com.hms.appointment.infrastructure.AppointmentRepository.QueueRow;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Check-in, walk-in and the live queue ([E5-3], APT-3). A token is the next number of the doctor and day (the day
 * is the check-in instant's date in the organization's time zone); the counter row lock serializes concurrent
 * check-ins of one doctor and day, so tokens are 1..n without gaps or repeats. Walk-ins take a token at once and
 * hold no slot (the V25 exclusion constraint ignores them). The walk-in {@code reason} is free text: audited by
 * field name only, never by value.
 */
@Service
public class QueueService {

    /** A queue line as the API returns it. */
    public record QueueEntry(UUID appointmentId, PatientBrief patient, int tokenNo, String status) {
    }

    /** The doctor's live queue for one day. */
    public record QueueView(UUID doctorId, LocalDate date, List<QueueEntry> items) {
    }

    private final AppointmentRepository repository;
    private final PractitionerAvailability practitioners;
    private final PatientDirectory patients;
    private final OrganizationDirectory organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public QueueService(AppointmentRepository repository, PractitionerAvailability practitioners,
            PatientDirectory patients, OrganizationDirectory organizations, AuditRecorder audit, UuidV7Generator ids,
            Clock clock) {
        this.repository = repository;
        this.practitioners = practitioners;
        this.patients = patients;
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** SCHEDULED to CHECKED_IN with the next token of the doctor and day; the token is allocated last. */
    @Transactional
    public AppointmentView checkIn(OrganizationPrincipal actor, UUID id, Long version) {
        UUID org = actor.organizationId();
        if (version == null) {
            throw AppointmentService.invalid("version is required.");
        }
        AppointmentRow row = repository.lock(org, id).orElseThrow(AppointmentService::notFound);
        AppointmentService.requireTransition(row, version, AppointmentStatus.CHECKED_IN);
        Instant now = clock.instant();
        LocalDate day = day(org, now);
        int token = repository.nextToken(org, row.practitionerId(), day);
        repository.checkIn(org, id, token, day, now);
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_CHECKED_IN", AppointmentService.ENTITY, id,
                AuditOutcome.SUCCESS, List.of("status", "tokenNo", "tokenDate")));
        return AppointmentService.withBrief(patients, org, AppointmentService.view(repository.find(org, id).orElseThrow()));
    }

    /**
     * An unannounced visit: a WALK_IN appointment born CHECKED_IN, starting now (to the minute, rounded down) for
     * the doctor's default slot length, outside the availability rules.
     */
    @Transactional
    public AppointmentView walkIn(OrganizationPrincipal actor, UUID patientId, UUID doctorId, String reason) {
        UUID org = actor.organizationId();
        if (patientId == null || doctorId == null) {
            throw AppointmentService.invalid("patientId and doctorId are required.");
        }
        String text = reason == null ? null : reason.strip();
        if (text != null && text.isEmpty()) {
            text = null;
        }
        if (text != null && text.length() > AppointmentService.MAX_REASON_LENGTH) {
            throw AppointmentService.invalid("reason must be at most " + AppointmentService.MAX_REASON_LENGTH
                    + " characters.");
        }
        int slot = practitioners.activeSlotMinutes(org, doctorId).orElseThrow(() -> new BusinessRuleException(
                "DOCTOR_NOT_AVAILABLE", "The doctor does not exist or is inactive."));
        Instant now = clock.instant();
        Instant start = now.truncatedTo(ChronoUnit.MINUTES);
        LocalDate day = day(org, now);
        UUID id = ids.next();
        int token = repository.nextToken(org, doctorId, day);
        switch (repository.insertWalkIn(new NewWalkIn(id, org, patientId, doctorId, start,
                start.plus(Duration.ofMinutes(slot)), text, actor.userId(), now, token, day))) {
            case UNKNOWN_PATIENT -> throw new BusinessRuleException("PATIENT_NOT_AVAILABLE",
                    "The patient does not exist.");
            case OVERLAP, INSERTED -> { }
        }
        List<String> fields = new ArrayList<>(List.of("patientId", "doctorId", "startAt", "endAt"));
        if (text != null) {
            fields.add("reason");
        }
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_BOOKED", AppointmentService.ENTITY, id,
                AuditOutcome.SUCCESS, fields));
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_CHECKED_IN", AppointmentService.ENTITY, id,
                AuditOutcome.SUCCESS, List.of("status", "tokenNo", "tokenDate")));
        return AppointmentService.withBrief(patients, org, AppointmentService.view(repository.find(org, id).orElseThrow()));
    }

    /**
     * The doctor's queue for {@code date} (default: today in the organization's zone): checked-in and
     * in-consultation patients by token. It names patients, so reading it is audited (filter names only). An unknown
     * or other-organization doctor is {@code 404}; a doctor with nobody waiting has an empty list.
     */
    @Transactional
    public QueueView queue(OrganizationPrincipal actor, UUID doctorId, LocalDate date) {
        UUID org = actor.organizationId();
        if (doctorId == null) {
            throw AppointmentService.invalid("doctorId is required.");
        }
        if (practitioners.defaultSlotMinutes(org, doctorId).isEmpty()) {
            throw new NotFoundException("Doctor not found.");
        }
        LocalDate day = date != null ? date : day(org, clock.instant());
        List<QueueRow> rows = repository.queue(org, doctorId, day);
        Map<UUID, PatientBrief> briefs = patients.briefs(org, rows.stream().map(QueueRow::patientId).distinct()
                .toList());
        List<QueueEntry> items = rows.stream().map(r -> new QueueEntry(r.appointmentId(),
                briefs.getOrDefault(r.patientId(), new PatientBrief(r.patientId(), null, null)), r.tokenNo(),
                r.status())).toList();
        audit.record(new AuditEntry(org, actor.userId(), "APPOINTMENT_QUEUE_VIEWED", AppointmentService.ENTITY, null,
                AuditOutcome.SUCCESS, date == null ? List.of("doctorId") : List.of("doctorId", "date")));
        return new QueueView(doctorId, day, items);
    }

    private LocalDate day(UUID org, Instant instant) {
        ZoneId zone = ZoneId.of(organizations.find(org).map(OrganizationSummary::timezone)
                .orElseThrow(() -> new IllegalStateException("organization not found")));
        return instant.atZone(zone).toLocalDate();
    }
}
