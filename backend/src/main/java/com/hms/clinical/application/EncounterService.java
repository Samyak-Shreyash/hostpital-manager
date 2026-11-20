package com.hms.clinical.application;

import com.hms.appointment.api.AppointmentLifecycle;
import com.hms.appointment.api.AppointmentLookup;
import com.hms.appointment.api.AppointmentLookup.AppointmentRef;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.clinical.api.EncounterFinalized;
import com.hms.clinical.api.EncounterQuery;
import com.hms.clinical.application.EncounterView.Diagnosis;
import com.hms.clinical.application.EncounterView.FollowUp;
import com.hms.clinical.application.EncounterView.Note;
import com.hms.clinical.application.EncounterView.Observation;
import com.hms.clinical.application.EncounterView.PatientRef;
import com.hms.clinical.application.EncounterView.Summary;
import com.hms.clinical.application.EncounterView.Warning;
import com.hms.clinical.domain.NoteKind;
import com.hms.clinical.domain.ObservationKind;
import com.hms.clinical.infrastructure.EncounterRepository;
import com.hms.clinical.infrastructure.EncounterRepository.DiagnosisRow;
import com.hms.clinical.infrastructure.EncounterRepository.EncounterRow;
import com.hms.clinical.infrastructure.EncounterRepository.NewEncounter;
import com.hms.clinical.infrastructure.EncounterRepository.ObservationRow;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.practitioner.api.PractitionerDirectory;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recording an encounter ([E6-1], OPD-3..OPD-6; api-design.md section 8). Only the treating practitioner (the
 * practitioner linked to the signed-in user) edits, unless the caller may edit any encounter. Every edit locks the
 * encounter row, refuses a FINALIZED one ({@code 409 ENCOUNTER_FINALIZED}), compares the caller's {@code version}
 * ({@code 409 CONCURRENT_MODIFICATION}) and bumps it. Starting from an appointment moves it to IN_CONSULTATION in the
 * same transaction. Chief complaint, notes, diagnosis descriptions, plan and instructions are PHI: audited by field
 * name only, never by value, never logged.
 */
@Service
public class EncounterService implements EncounterQuery {

    static final String ENTITY = "ENCOUNTER";
    static final int MAX_COMPLAINT = 1000;
    static final int MAX_NOTE = 10000;
    static final int MAX_DIAGNOSES = 20;
    static final int MAX_INSTRUCTIONS = 2000;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<NoteKind> EDITABLE_NOTES = EnumSet.of(NoteKind.SYMPTOMS, NoteKind.EXAMINATION,
            NoteKind.ASSESSMENT);

    /** {@code mayEditAny} is the caller's {@code encounter:update-any}. */
    public record StartCommand(UUID appointmentId, UUID patientId, String chiefComplaint, boolean mayEditAny) {
    }

    /** One requested vital; {@code kind} and {@code value} are validated here (422), not by the JSON binder. */
    public record ObservationInput(String kind, BigDecimal value, String unit) {
    }

    public record DiagnosisInput(String code, String description, Boolean primary) {
    }

    private final EncounterRepository repository;
    private final AppointmentLookup appointments;
    private final AppointmentLifecycle lifecycle;
    private final PatientDirectory patients;
    private final PractitionerDirectory practitioners;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public EncounterService(EncounterRepository repository, AppointmentLookup appointments,
            AppointmentLifecycle lifecycle, PatientDirectory patients, PractitionerDirectory practitioners,
            AuditRecorder audit, UuidV7Generator ids, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.appointments = appointments;
        this.lifecycle = lifecycle;
        this.patients = patients;
        this.practitioners = practitioners;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.events = events;
    }

    @Transactional
    public EncounterView start(OrganizationPrincipal actor, StartCommand cmd) {
        UUID org = actor.organizationId();
        String complaint = text(cmd.chiefComplaint(), MAX_COMPLAINT, "chiefComplaint", false);
        UUID patientId;
        UUID practitionerId;
        Optional<UUID> own = practitioners.activePractitionerOfUser(org, actor.userId());
        if (cmd.appointmentId() != null) {
            AppointmentRef appointment = appointments.find(org, cmd.appointmentId())
                    .orElseThrow(() -> new NotFoundException("Appointment not found."));
            patientId = appointment.patientId();
            practitionerId = appointment.practitionerId();
            requireTreating(own, practitionerId, cmd.mayEditAny());
            // Locks the appointment row and applies the state machine: only a CHECKED_IN appointment starts.
            lifecycle.markInConsultation(org, cmd.appointmentId());
        } else {
            if (cmd.patientId() == null) {
                throw invalid("appointmentId or patientId is required.");
            }
            patientId = cmd.patientId();
            practitionerId = own.orElseThrow(() -> new BusinessRuleException("NOT_A_PRACTITIONER",
                    "Only a user linked to a practitioner record can start an encounter."));
        }
        PatientBrief patient = patients.briefs(org, List.of(patientId)).get(patientId);
        if (patient == null) {
            throw new BusinessRuleException("PATIENT_NOT_AVAILABLE", "The patient does not exist.");
        }
        UUID id = ids.next();
        Instant now = clock.instant();
        if (!repository.insert(new NewEncounter(id, org, patientId, practitionerId, cmd.appointmentId(), complaint,
                actor.userId(), now))) {
            throw new ConflictException("ENCOUNTER_EXISTS", "The appointment already has an encounter.");
        }
        List<String> fields = new ArrayList<>(List.of("patientId", "practitionerId"));
        if (cmd.appointmentId() != null) {
            fields.add("appointmentId");
        }
        if (complaint != null) {
            fields.add("chiefComplaint");
        }
        audit.record(new AuditEntry(org, actor.userId(), "ENCOUNTER_STARTED", ENTITY, id, AuditOutcome.SUCCESS,
                fields));
        return view(org, repository.find(org, id).orElseThrow(), patient);
    }

    /** Reading an encounter is audited (clinical record). */
    @Transactional
    public EncounterView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        EncounterRow row = repository.find(org, id).orElseThrow(EncounterService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "ENCOUNTER_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return view(org, row, patients.briefs(org, List.of(row.patientId())).get(row.patientId()));
    }

    /** A patient's encounters, newest first; audited against the patient. */
    @Transactional
    public PageResult<Summary> history(OrganizationPrincipal actor, UUID patientId, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        UUID org = actor.organizationId();
        if (!patients.briefs(org, List.of(patientId)).containsKey(patientId)) {
            throw new NotFoundException("Patient not found.");
        }
        List<Summary> items = repository.listByPatient(org, patientId, size, (long) page * size).stream()
                .map(r -> new Summary(r.id(), r.practitionerId(), r.appointmentId(), r.status(), r.chiefComplaint(),
                        r.startedAt(), r.finalizedAt())).toList();
        audit.record(AuditEntry.of(org, actor.userId(), "ENCOUNTER_HISTORY_VIEWED", "PATIENT", patientId,
                AuditOutcome.SUCCESS));
        return new PageResult<>(items, page, size, repository.countByPatient(org, patientId));
    }

    /** Replaces the vitals set. */
    @Transactional
    public EncounterView replaceObservations(OrganizationPrincipal actor, UUID id, Long version,
            List<ObservationInput> inputs, boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        if (inputs == null) {
            throw invalid("observations is required.");
        }
        List<ObservationRow> rows = new ArrayList<>();
        Set<ObservationKind> seen = EnumSet.noneOf(ObservationKind.class);
        for (ObservationInput in : inputs) {
            if (in == null || in.kind() == null || in.value() == null || in.unit() == null
                    || in.unit().isBlank() || in.unit().strip().length() > 16) {
                throw invalid("Each observation needs kind, value and a unit of at most 16 characters.");
            }
            ObservationKind kind = parse(ObservationKind.class, in.kind(), "observation kind");
            if (!seen.add(kind)) {
                throw invalid("Observation kind " + kind + " appears more than once.");
            }
            if (in.value().scale() > 2) {
                throw invalid("Observation values have at most 2 decimals.");
            }
            if (!kind.withinLimits(in.value())) {
                throw new BusinessRuleException("VALIDATION_ERROR",
                        kind + " must be " + kind.limitsText() + ".");
            }
            rows.add(new ObservationRow(kind.name(), in.value(), in.unit().strip()));
        }
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.replaceObservations(org, id, rows, actor.userId(), now);
        return saved(actor, row, "ENCOUNTER_OBSERVATIONS_UPDATED", List.of("observations"), now);
    }

    /** Writes the current note of a kind (SYMPTOMS, EXAMINATION, ASSESSMENT); the earlier text is kept as history. */
    @Transactional
    public EncounterView putNote(OrganizationPrincipal actor, UUID id, String kindName, Long version, String text,
            boolean mayEditAny) {
        UUID org = actor.organizationId();
        NoteKind kind = parse(NoteKind.class, kindName, "note kind");
        if (!EDITABLE_NOTES.contains(kind)) {
            throw invalid("Note kind must be one of " + EDITABLE_NOTES + ".");
        }
        requireVersion(version);
        String body = text(text, MAX_NOTE, "text", true);
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.replaceNote(org, id, ids.next(), kind.name(), body, actor.userId(), now);
        return saved(actor, row, "ENCOUNTER_NOTE_UPDATED", List.of("notes." + kind.name().toLowerCase()), now);
    }

    @Transactional
    public EncounterView replaceDiagnoses(OrganizationPrincipal actor, UUID id, Long version,
            List<DiagnosisInput> inputs, boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        if (inputs == null || inputs.size() > MAX_DIAGNOSES) {
            throw invalid("diagnoses is required, at most " + MAX_DIAGNOSES + " entries.");
        }
        List<DiagnosisRow> rows = new ArrayList<>();
        int primaries = 0;
        for (DiagnosisInput in : inputs) {
            if (in == null) {
                throw invalid("A diagnosis must not be null.");
            }
            String code = text(in.code(), 20, "code", false);
            String description = text(in.description(), 500, "description", true);
            boolean primary = Boolean.TRUE.equals(in.primary());
            primaries += primary ? 1 : 0;
            rows.add(new DiagnosisRow(code, description, primary));
        }
        if (primaries > 1) {
            throw invalid("At most one diagnosis can be primary.");
        }
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.replaceDiagnoses(org, id, rows, actor.userId(), now);
        return saved(actor, row, "ENCOUNTER_DIAGNOSES_UPDATED", List.of("diagnoses"), now);
    }

    @Transactional
    public EncounterView putTreatmentPlan(OrganizationPrincipal actor, UUID id, Long version, String text,
            boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        String body = text(text, MAX_NOTE, "text", true);
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.upsertPlan(org, id, body, actor.userId(), now);
        return saved(actor, row, "ENCOUNTER_TREATMENT_PLAN_UPDATED", List.of("treatmentPlan"), now);
    }

    @Transactional
    public EncounterView putFollowUp(OrganizationPrincipal actor, UUID id, Long version, LocalDate date,
            String instructions, boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        if (date == null) {
            throw invalid("date is required.");
        }
        String body = text(instructions, MAX_INSTRUCTIONS, "instructions", false);
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.upsertFollowUp(org, id, date, body, actor.userId(), now);
        return saved(actor, row, "ENCOUNTER_FOLLOW_UP_UPDATED", List.of("followUp"), now);
    }

    /**
     * Locks the encounter for good ([E6-2]): treating doctor (or encounter:update-any), OPEN, current version. Marks
     * the appointment COMPLETED when there is one (ad hoc encounters have none) and publishes
     * {@link EncounterFinalized} in the same transaction.
     */
    @Transactional
    public EncounterView finalizeEncounter(OrganizationPrincipal actor, UUID id, Long version, boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        EncounterRow row = editable(org, id, version, actor, mayEditAny);
        Instant now = clock.instant();
        repository.finalizeEncounter(org, id, actor.userId(), now);
        if (row.appointmentId() != null) {
            lifecycle.markCompleted(org, row.appointmentId());
        }
        audit.record(new AuditEntry(org, actor.userId(), "ENCOUNTER_FINALIZED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("status", "finalizedAt")));
        events.publishEvent(new EncounterFinalized(org, id, row.patientId(), row.practitionerId(),
                row.appointmentId(), now));
        return view(org, repository.find(org, id).orElseThrow(),
                patients.briefs(org, List.of(row.patientId())).get(row.patientId()));
    }

    /**
     * Appends an addendum to a FINALIZED encounter (anything else is {@code 409 INVALID_STATE_TRANSITION}). The
     * encounter row itself is immutable, so its version does not change. The text is audited by field name only.
     */
    @Transactional
    public EncounterView addAddendum(OrganizationPrincipal actor, UUID id, String text, boolean mayEditAny) {
        UUID org = actor.organizationId();
        String body = text(text, MAX_NOTE, "text", true);
        EncounterRow row = repository.lock(org, id).orElseThrow(EncounterService::notFound);
        requireTreating(practitioners.activePractitionerOfUser(org, actor.userId()), row.practitionerId(),
                mayEditAny);
        if (!"FINALIZED".equals(row.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "An addendum can only be added to a finalized encounter.");
        }
        repository.addAddendum(org, id, ids.next(), body, actor.userId(), clock.instant());
        audit.record(new AuditEntry(org, actor.userId(), "ENCOUNTER_ADDENDUM_ADDED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("addendum")));
        return view(org, repository.find(org, id).orElseThrow(),
                patients.briefs(org, List.of(row.patientId())).get(row.patientId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EncounterSummary> find(UUID organizationId, UUID encounterId) {
        return repository.find(organizationId, encounterId)
                .map(r -> new EncounterSummary(r.id(), r.patientId(), r.practitionerId(), r.status()));
    }

    // --- internals ---

    /**
     * 404, then 403 (a caller who may not edit learns nothing about the state), then the lock-and-check of state
     * and version on the locked row.
     */
    private EncounterRow editable(UUID org, UUID id, long version, OrganizationPrincipal actor, boolean mayEditAny) {
        EncounterRow row = repository.lock(org, id).orElseThrow(EncounterService::notFound);
        requireTreating(practitioners.activePractitionerOfUser(org, actor.userId()), row.practitionerId(),
                mayEditAny);
        if (!"OPEN".equals(row.status())) {
            throw new ConflictException("ENCOUNTER_FINALIZED", "The encounter is finalized; add an addendum.");
        }
        if (row.version() != version) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
        return row;
    }

    private EncounterView saved(OrganizationPrincipal actor, EncounterRow row, String action, List<String> fields,
            Instant now) {
        UUID org = actor.organizationId();
        repository.touch(org, row.id(), now);
        audit.record(new AuditEntry(org, actor.userId(), action, ENTITY, row.id(), AuditOutcome.SUCCESS, fields));
        return view(org, repository.find(org, row.id()).orElseThrow(),
                patients.briefs(org, List.of(row.patientId())).get(row.patientId()));
    }

    private static void requireTreating(Optional<UUID> own, UUID treating, boolean mayEditAny) {
        if (!mayEditAny && !own.map(treating::equals).orElse(false)) {
            throw new AccessDeniedException("Only the treating practitioner, or a holder of encounter:update-any,"
                    + " can change this encounter.");
        }
    }

    private EncounterView view(UUID org, EncounterRow r, PatientBrief patient) {
        List<Warning> warnings = new ArrayList<>();
        List<Observation> observations = new ArrayList<>();
        for (ObservationRow o : repository.observations(org, r.id())) {
            observations.add(new Observation(o.kind(), o.value().toPlainString(), o.unit()));
            ObservationKind kind = ObservationKind.valueOf(o.kind());
            if (kind.implausible(o.value())) {
                warnings.add(new Warning(o.kind(), o.kind() + " is outside its usual range; please check the value."));
            }
        }
        List<Note> notes = repository.notes(org, r.id()).stream()
                .map(n -> new Note(n.kind(), n.text(), n.authorId(), n.createdAt())).toList();
        List<Diagnosis> diagnoses = repository.diagnoses(org, r.id()).stream()
                .map(d -> new Diagnosis(d.code(), d.description(), d.primary())).toList();
        FollowUp followUp = repository.followUp(org, r.id()).map(f -> new FollowUp(f.date(), f.instructions()))
                .orElse(null);
        return new EncounterView(r.id(), new PatientRef(r.patientId(), patient == null ? null : patient.uhid()),
                r.practitionerId(), r.appointmentId(), r.status(), r.chiefComplaint(), r.version(), r.startedAt(),
                r.finalizedAt(), observations, warnings, notes, diagnoses,
                repository.plan(org, r.id()).orElse(null), followUp);
    }

    private static void requireVersion(Long version) {
        if (version == null) {
            throw invalid("version is required.");
        }
    }

    /** Trims; blank is null (or an error when required); over-long is an error. Never echoes the text. */
    private static String text(String value, int max, String field, boolean required) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            if (required) {
                throw invalid(field + " is required.");
            }
            return null;
        }
        if (stripped.length() > max) {
            throw invalid(field + " must be at most " + max + " characters.");
        }
        return stripped;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String name, String what) {
        try {
            return Enum.valueOf(type, name.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid("Unknown " + what + ".");
        }
    }

    static NotFoundException notFound() {
        return new NotFoundException("Encounter not found.");
    }

    static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
