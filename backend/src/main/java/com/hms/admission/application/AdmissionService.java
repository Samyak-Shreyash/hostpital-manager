package com.hms.admission.application;

import com.hms.admission.application.AdmissionView.CurrentBed;
import com.hms.admission.domain.AdmissionStatus;
import com.hms.admission.infrastructure.AdmissionRepository;
import com.hms.admission.infrastructure.AdmissionRepository.AdmissionRow;
import com.hms.admission.infrastructure.AdmissionRepository.ListFilter;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.api.BedAllocation;
import com.hms.billing.api.InvoiceLookup;
import com.hms.bedmanagement.api.BedAllocation.AssignmentView;
import com.hms.clinical.api.EncounterQuery;
import com.hms.clinical.api.EncounterQuery.EncounterSummary;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.practitioner.api.PractitionerAvailability;
import com.hms.practitioner.api.PractitionerDirectory;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Admissions ([E8-2], [E8-3]; api-design.md section 11). An admission always starts in a bed (ruling R5).
 *
 * <p>Admit runs in one transaction in the M4 lock order: validate patient, doctor and source encounter, lock the bed
 * ({@link BedAllocation#lockAvailable}), insert the admission (I3 partial unique index: {@code 409
 * PATIENT_ALREADY_ADMITTED}) with its number (allocated just before the insert; the sequence row is the last lock
 * taken) and open the assignment (I1: {@code 409 BED_NOT_AVAILABLE}). The diagnosis is clinical free text: its value is never audited or logged.
 */
@Service
public class AdmissionService {

    static final String ENTITY = "ADMISSION";
    static final String DOCUMENT_TYPE = "ADMISSION";
    static final int MAX_DIAGNOSIS_LENGTH = 1000;
    static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Arrays.stream(AdmissionStatus.values()).map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    static final int MAX_REASON_LENGTH = 500;

    /** Result of a transfer: the new open assignment and the one it closed (same instant). */
    public record TransferView(AssignmentView currentAssignment, AssignmentView previousAssignment) {
    }

    public record AdmitCommand(UUID patientId, UUID admittingDoctorId, UUID bedId, String admissionDiagnosis,
            UUID sourceEncounterId) {
    }

    private final AdmissionRepository admissions;
    private final BedAllocation beds;
    private final PatientDirectory patients;
    private final PractitionerDirectory practitioners;
    private final PractitionerAvailability practitionerStatus;
    private final EncounterQuery encounters;
    private final OrganizationDirectory organizations;
    private final DocumentNumberAllocator numbers;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final InvoiceLookup invoices;

    public AdmissionService(AdmissionRepository admissions, BedAllocation beds, PatientDirectory patients,
            PractitionerDirectory practitioners, PractitionerAvailability practitionerStatus, EncounterQuery encounters,
            OrganizationDirectory organizations, DocumentNumberAllocator numbers, AuditRecorder audit,
            UuidV7Generator ids, Clock clock, PlatformTransactionManager transactionManager, InvoiceLookup invoices) {
        this.invoices = invoices;
        this.admissions = admissions;
        this.beds = beds;
        this.patients = patients;
        this.practitioners = practitioners;
        this.practitionerStatus = practitionerStatus;
        this.encounters = encounters;
        this.organizations = organizations;
        this.numbers = numbers;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Transactional
    public AdmissionView admit(OrganizationPrincipal actor, AdmitCommand cmd) {
        UUID org = actor.organizationId();
        if (cmd.patientId() == null || cmd.admittingDoctorId() == null || cmd.bedId() == null) {
            throw invalid("patientId, admittingDoctorId and bedId are required.");
        }
        String diagnosis = diagnosis(cmd.admissionDiagnosis());
        if (!patients.briefs(org, List.of(cmd.patientId())).containsKey(cmd.patientId())) {
            throw new NotFoundException("Patient not found.");
        }
        if (practitioners.brief(org, cmd.admittingDoctorId()).isEmpty()) {
            throw new NotFoundException("Doctor not found.");
        }
        if (!practitionerStatus.isActive(org, cmd.admittingDoctorId())) {
            throw new BusinessRuleException("The admitting doctor is inactive.");
        }
        if (cmd.sourceEncounterId() != null) {
            EncounterSummary encounter = encounters.find(org, cmd.sourceEncounterId())
                    .orElseThrow(() -> new NotFoundException("Encounter not found."));
            if (!encounter.patientId().equals(cmd.patientId())) {
                throw invalid("sourceEncounterId belongs to another patient.");
            }
        }

        beds.lockAvailable(org, cmd.bedId());
        UUID id = ids.next();
        Instant now = now();
        String admissionNo = admissionNo(org, now);
        if (!admissions.insert(id, org, admissionNo, cmd.patientId(), cmd.admittingDoctorId(),
                cmd.sourceEncounterId(), diagnosis, now, actor.userId())) {
            throw new ConflictException("PATIENT_ALREADY_ADMITTED", "The patient already has an active admission.");
        }
        beds.open(org, cmd.bedId(), id, now);

        List<String> fields = new ArrayList<>(List.of("patientId", "admittingDoctorId", "admissionDiagnosis",
                "admittedAt", "status"));
        if (cmd.sourceEncounterId() != null) {
            fields.add("sourceEncounterId");
        }
        audit.record(new AuditEntry(org, actor.userId(), "ADMISSION_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                fields));
        audit.record(new AuditEntry(org, actor.userId(), "BED_ASSIGNED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("bedId", "assignedAt")));
        return view(org, admissions.find(org, id).orElseThrow());
    }

    /** {@code ADM-<calendar year in the organization's time zone>-<4-digit sequence>}. */
    private String admissionNo(UUID org, Instant now) {
        ZoneId zone = ZoneId.of(organizations.find(org).map(OrganizationSummary::timezone)
                .orElseThrow(() -> new IllegalStateException("organization not found")));
        int year = LocalDate.ofInstant(now, zone).getYear();
        long n = numbers.next(org, DOCUMENT_TYPE, "CY" + year);
        return String.format("ADM-%d-%04d", year, n);
    }

    /** Changes the admission diagnosis of an active admission; {@code version} must match. */
    @Transactional
    public AdmissionView update(OrganizationPrincipal actor, UUID id, Long version, String admissionDiagnosis) {
        UUID org = actor.organizationId();
        if (version == null) {
            throw invalid("version is required.");
        }
        String diagnosis = diagnosis(admissionDiagnosis);
        AdmissionRow row = admissions.lock(org, id).orElseThrow(AdmissionService::notFound);
        requireVersion(row, version);
        if (!AdmissionStatus.valueOf(row.status()).isActive()) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "An admission that is " + row.status() + " cannot be changed.");
        }
        if (diagnosis.equals(row.admissionDiagnosis())) {
            return view(org, row);
        }
        if (!admissions.updateDiagnosis(org, id, version, diagnosis, now())) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "ADMISSION_UPDATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("admissionDiagnosis")));
        return view(org, admissions.find(org, id).orElseThrow());
    }

    /**
     * ADMITTED to CANCELLED (an admission made in error), releasing the bed now. Locks the bed before the admission
     * row (lock order) through {@link #underBedLock}. A cancel with a live IPD invoice is refused ([E8-6],
     * {@code 409 INVALID_STATE_TRANSITION}); billing's check holds the admission's invoicing lock to the commit.
     */
    public AdmissionView cancel(OrganizationPrincipal actor, UUID id, Long version) {
        UUID org = actor.organizationId();
        if (version == null) {
            throw invalid("version is required.");
        }
        return underBedLock(org, id, held -> null, () -> { }, (row, held) -> {
            requireVersion(row, version);
            AdmissionStatus current = AdmissionStatus.valueOf(row.status());
            if (!current.canTransitionTo(AdmissionStatus.CANCELLED)) {
                throw new ConflictException("INVALID_STATE_TRANSITION",
                        "An admission that is " + current + " cannot be cancelled.");
            }
            if (invoices.hasLiveIpdInvoice(org, id)) {
                throw new ConflictException("INVALID_STATE_TRANSITION",
                        "The admission has a live IPD invoice; discard or cancel it first.");
            }
            Instant now = now();
            beds.release(org, id, now);
            if (!admissions.updateStatus(org, id, version, AdmissionStatus.CANCELLED.name(), now)) {
                throw concurrentModification();
            }
            audit.record(new AuditEntry(org, actor.userId(), "ADMISSION_CANCELLED", ENTITY, id,
                    AuditOutcome.SUCCESS, List.of("status")));
            audit.record(new AuditEntry(org, actor.userId(), "BED_RELEASED", ENTITY, id, AuditOutcome.SUCCESS,
                    List.of("bedId", "releasedAt")));
            return view(org, admissions.find(org, id).orElseThrow());
        });
    }

    /**
     * Moves an active admission to another bed ([E8-4]): locks both beds in ascending id order, checks the target is
     * available ({@code 409 BED_NOT_AVAILABLE}), locks the admission row (ADMITTED or DISCHARGE_INITIATED, else
     * {@code 409 INVALID_STATE_TRANSITION}), then closes the old assignment and opens the new one at the same
     * instant. The reason is stored on the new assignment and never audited.
     */
    public TransferView transfer(OrganizationPrincipal actor, UUID id, UUID toBedId, String reason) {
        UUID org = actor.organizationId();
        if (toBedId == null) {
            throw invalid("toBedId is required.");
        }
        String why = reason == null ? "" : reason.strip();
        if (why.isEmpty() || why.length() > MAX_REASON_LENGTH) {
            throw invalid("reason is required (1 to " + MAX_REASON_LENGTH + " characters).");
        }
        return underBedLock(org, id, held -> {
            if (held != null && held.bedId().equals(toBedId)) {
                throw invalid("toBedId is the bed the admission already holds.");
            }
            return toBedId;
        }, () -> beds.lockAvailable(org, toBedId), (row, held) -> {
            AdmissionStatus current = AdmissionStatus.valueOf(row.status());
            if (!current.isActive() || held == null) {
                throw new ConflictException("INVALID_STATE_TRANSITION",
                        "An admission that is " + current + " cannot change beds.");
            }
            Instant now = now();
            AssignmentView previous = beds.release(org, id, now);
            AssignmentView next = beds.open(org, toBedId, id, now, why);
            audit.record(new AuditEntry(org, actor.userId(), "BED_TRANSFERRED", ENTITY, id, AuditOutcome.SUCCESS,
                    List.of("fromBedId", "toBedId", "releasedAt", "assignedAt")));
            return new TransferView(next, previous);
        });
    }

    /** Work done while holding the bed lock(s) and the admission row lock. */
    @FunctionalInterface
    interface LockedWork<T> {
        T run(AdmissionRow row, AssignmentView held);
    }

    /** The admission's open assignment changed between the unlocked read and the locks. */
    private static final class StaleHold extends RuntimeException {
        StaleHold() {
            super(null, null, false, false);
        }
    }

    /**
     * Shared by cancel, transfer and discharge ({@link DischargeService}). In a new transaction: reads the admission's open assignment (unlocked), locks
     * its bed plus {@code extraBed.apply(held)} (when not null) in ascending id order, runs {@code afterBedLocks},
     * locks the admission row and re-reads the open assignment. If that is no longer the one read first, the
     * transaction rolls back and the whole attempt is retried once in a fresh transaction (so no lock is ever taken
     * out of order); a second miss is {@code 409 CONCURRENT_MODIFICATION}.
     *
     * <p>Must be called with no transaction open: the template uses REQUIRED propagation, so inside an outer
     * transaction a miss would mark that transaction rollback-only and the retry would not be fresh. Callers
     * (including a later discharge flow) must therefore not be {@code @Transactional}; this fails fast otherwise.
     */
    <T> T underBedLock(UUID org, UUID id, Function<AssignmentView, UUID> extraBed, Runnable afterBedLocks,
            LockedWork<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("underBedLock must run outside a transaction");
        }
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.execute(status -> {
                    admissions.find(org, id).orElseThrow(AdmissionService::notFound);
                    AssignmentView held = beds.current(org, List.of(id)).get(id);
                    List<UUID> toLock = new ArrayList<>();
                    if (held != null) {
                        toLock.add(held.bedId());
                    }
                    UUID extra = extraBed.apply(held);
                    if (extra != null) {
                        toLock.add(extra);
                    }
                    if (!toLock.isEmpty()) {
                        beds.lockBeds(org, toLock);
                    }
                    afterBedLocks.run();
                    AdmissionRow row = admissions.lock(org, id).orElseThrow(AdmissionService::notFound);
                    AssignmentView still = beds.current(org, List.of(id)).get(id);
                    if (!Objects.equals(held == null ? null : held.id(), still == null ? null : still.id())) {
                        throw new StaleHold();
                    }
                    return work.run(row, held);
                });
            } catch (StaleHold e) {
                if (attempt >= 1) {
                    throw concurrentModification();
                }
            }
        }
    }

    /** Reading an admission is audited like a patient view: it ties a patient to an inpatient stay. */
    @Transactional
    public AdmissionView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        AdmissionRow row = admissions.find(org, id).orElseThrow(AdmissionService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "ADMISSION_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return view(org, row);
    }

    /** Bed history of the admission, oldest first; audited as a view of the admission. */
    @Transactional
    public List<AssignmentView> bedAssignments(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        admissions.find(org, id).orElseThrow(AdmissionService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "ADMISSION_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return beds.history(org, id);
    }

    /** Admissions, newest first. Audited like a read (names of the filters used only). */
    @Transactional
    public PageResult<AdmissionView> list(OrganizationPrincipal actor, String status, UUID wardId, UUID patientId,
            int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        if (status != null && !STATUSES.contains(status)) {
            throw invalid("status must be one of " + STATUSES + ".");
        }
        UUID org = actor.organizationId();
        ListFilter filter = new ListFilter(status, patientId,
                wardId == null ? null : beds.admissionsInWard(org, wardId));
        List<AdmissionView> items = views(org, admissions.list(org, filter, size, (long) page * size));
        List<String> used = new ArrayList<>();
        if (status != null) {
            used.add("status");
        }
        if (wardId != null) {
            used.add("wardId");
        }
        if (patientId != null) {
            used.add("patientId");
        }
        audit.record(new AuditEntry(org, actor.userId(), "ADMISSION_LISTED", ENTITY, null, AuditOutcome.SUCCESS,
                used));
        return new PageResult<>(items, page, size, admissions.count(org, filter));
    }

    private AdmissionView view(UUID org, AdmissionRow row) {
        return views(org, List.of(row)).get(0);
    }

    /** Adds patient briefs and current beds with one batch lookup each. */
    private List<AdmissionView> views(UUID org, List<AdmissionRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, PatientBrief> briefs = patients.briefs(org, rows.stream().map(AdmissionRow::patientId).distinct()
                .toList());
        Map<UUID, AssignmentView> held = beds.current(org, rows.stream().map(AdmissionRow::id).toList());
        return rows.stream().map(r -> {
            AssignmentView a = held.get(r.id());
            return new AdmissionView(r.id(), r.admissionNo(), r.patientId(),
                    briefs.getOrDefault(r.patientId(), new PatientBrief(r.patientId(), null, null)),
                    r.admittingDoctorId(), r.sourceEncounterId(), r.admissionDiagnosis(), r.status(), r.admittedAt(),
                    r.plannedDischargeAt(), r.dischargedAt(),
                    a == null ? null : new CurrentBed(a.bedId(), a.bedNo(), a.wardName()), r.version(),
                    r.createdAt(), r.updatedAt());
        }).toList();
    }

    private static String diagnosis(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) {
            throw invalid("admissionDiagnosis is required.");
        }
        if (text.length() > MAX_DIAGNOSIS_LENGTH) {
            throw invalid("admissionDiagnosis must be at most " + MAX_DIAGNOSIS_LENGTH + " characters.");
        }
        return text;
    }

    private static void requireVersion(AdmissionRow row, long version) {
        if (row.version() != version) {
            throw concurrentModification();
        }
    }

    static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }

    static NotFoundException notFound() {
        return new NotFoundException("Admission not found.");
    }

    static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
