package com.hms.admission.application;

import com.hms.admission.api.PatientDischarged;
import com.hms.admission.domain.AdmissionStatus;
import com.hms.admission.infrastructure.AdmissionRepository;
import com.hms.admission.infrastructure.AdmissionRepository.AdmissionRow;
import com.hms.admission.infrastructure.DischargeSummaryRepository;
import com.hms.admission.infrastructure.DischargeSummaryRepository.MedicationRow;
import com.hms.admission.infrastructure.DischargeSummaryRepository.SummaryRow;
import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.bedmanagement.api.BedAllocation;
import com.hms.billing.api.InvoiceLookup;
import com.hms.billing.api.InvoiceLookup.FinalInvoice;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Discharge summary and discharge ([E8-7]; api-design.md section 11; rulings R6, R7).
 *
 * <p>Saving the summary locks the admission row (ADMITTED or DISCHARGE_INITIATED; the first save moves it to
 * DISCHARGE_INITIATED) and stores {@code plannedDischargeAt} on the admission, where billing reads it as the FINAL
 * invoice's charge end. Changing the planned time while a live FINAL invoice exists is refused. Clinical text is never
 * audited or logged: audit carries field names only.
 *
 * <p>Discharge is one transaction through {@link AdmissionService#underBedLock} (bed, then admission row, then the
 * billing advisory lock): status and version, time window, billing rule, bed release at {@code dischargedAt},
 * DISCHARGED. Any failure rolls the whole thing back, so the bed stays assigned.
 */
@Service
public class DischargeService {

    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    static final int MAX_MEDICATIONS = 50;

    public record Medication(String name, String dose, String frequency, String duration) {
    }

    public record SummaryCommand(Long version, String finalDiagnosis, String courseInHospital, String procedures,
            String conditionAtDischarge, List<Medication> medications, String followUp, Instant plannedDischargeAt) {
    }

    public record SummaryView(UUID id, UUID admissionId, String finalDiagnosis, String courseInHospital,
            String procedures, String conditionAtDischarge, List<Medication> medications, String followUp,
            Instant plannedDischargeAt, long version, Instant createdAt, Instant updatedAt) {
    }

    public record DischargeCommand(Long version, Instant dischargedAt, UUID invoiceId, boolean deferBilling) {
    }

    public record DischargeResult(String status, boolean bedReleased) {
    }

    private final AdmissionService admissionService;
    private final AdmissionRepository admissions;
    private final DischargeSummaryRepository summaries;
    private final BedAllocation beds;
    private final InvoiceLookup invoices;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final ApplicationEventPublisher events;

    public DischargeService(AdmissionService admissionService, AdmissionRepository admissions,
            DischargeSummaryRepository summaries, BedAllocation beds, InvoiceLookup invoices, AuditRecorder audit,
            UuidV7Generator ids, ApplicationEventPublisher events) {
        this.admissionService = admissionService;
        this.admissions = admissions;
        this.summaries = summaries;
        this.beds = beds;
        this.invoices = invoices;
        this.audit = audit;
        this.ids = ids;
        this.events = events;
    }

    @Transactional
    public SummaryView saveSummary(OrganizationPrincipal actor, UUID id, SummaryCommand cmd) {
        UUID org = actor.organizationId();
        SummaryRow values = validate(cmd);
        List<MedicationRow> medications = medications(cmd.medications());
        AdmissionRow row = admissions.lock(org, id).orElseThrow(AdmissionService::notFound);
        AdmissionStatus status = AdmissionStatus.valueOf(row.status());
        if (!status.isActive()) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "The discharge summary of an admission that is " + status + " cannot be changed.");
        }
        Instant planned = cmd.plannedDischargeAt().truncatedTo(ChronoUnit.MICROS);
        if (planned.isBefore(row.admittedAt())) {
            throw AdmissionService.invalid("plannedDischargeAt must not be before admittedAt.");
        }
        Optional<SummaryRow> existing = summaries.findByAdmission(org, id);
        if (existing.isPresent() && !Objects.equals(cmd.version(), existing.get().version())) {
            throw AdmissionService.concurrentModification();
        }
        boolean plannedChanged = !planned.equals(row.plannedDischargeAt());
        if (plannedChanged && invoices.liveFinalInvoice(org, id).isPresent()) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "A final invoice exists for the planned discharge; discard it before changing the time.");
        }

        Instant now = admissionService.now();
        UUID summaryId;
        if (existing.isEmpty()) {
            summaryId = ids.next();
            summaries.insert(summaryId, org, id, values, actor.userId(), now);
        } else {
            summaryId = existing.get().id();
            if (!summaries.update(org, summaryId, existing.get().version(), values, actor.userId(), now)) {
                throw AdmissionService.concurrentModification();
            }
        }
        summaries.replaceMedications(org, summaryId, medications,
                medications.stream().map(m -> ids.next()).toList());
        boolean initiate = status == AdmissionStatus.ADMITTED;
        if (plannedChanged || initiate) {
            if (!admissions.updatePlannedDischarge(org, id, row.version(),
                    AdmissionStatus.DISCHARGE_INITIATED.name(), planned, now)) {
                throw AdmissionService.concurrentModification();
            }
        }

        List<String> fields = new ArrayList<>(List.of("finalDiagnosis", "conditionAtDischarge", "medications"));
        if (values.courseInHospital() != null) {
            fields.add("courseInHospital");
        }
        if (values.procedures() != null) {
            fields.add("procedures");
        }
        if (values.followUp() != null) {
            fields.add("followUp");
        }
        if (plannedChanged) {
            fields.add("plannedDischargeAt");
        }
        if (initiate) {
            fields.add("status");
        }
        audit.record(new AuditEntry(org, actor.userId(), "DISCHARGE_SUMMARY_SAVED", AdmissionService.ENTITY, id,
                AuditOutcome.SUCCESS, fields));
        return view(org, summaries.findByAdmission(org, id).orElseThrow(), planned);
    }

    /** The summary of an admission; {@code 404} when there is none yet. Audited as a view of the admission. */
    @Transactional
    public SummaryView getSummary(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        AdmissionRow row = admissions.find(org, id).orElseThrow(AdmissionService::notFound);
        SummaryRow summary = summaries.findByAdmission(org, id)
                .orElseThrow(() -> new NotFoundException("Discharge summary not found."));
        audit.record(new AuditEntry(org, actor.userId(), "DISCHARGE_SUMMARY_VIEWED", AdmissionService.ENTITY, id,
                AuditOutcome.SUCCESS, List.of()));
        return view(org, summary, row.plannedDischargeAt());
    }

    /**
     * DISCHARGE_INITIATED to DISCHARGED in one transaction. Not {@code @Transactional}: {@link
     * AdmissionService#underBedLock} opens the single transaction (lock order bed, admission, billing).
     *
     * @param mayDeferBilling whether the caller holds {@code discharge:defer-billing}
     */
    public DischargeResult discharge(OrganizationPrincipal actor, UUID id, DischargeCommand cmd,
            boolean mayDeferBilling) {
        UUID org = actor.organizationId();
        if (cmd.version() == null || cmd.dischargedAt() == null) {
            throw AdmissionService.invalid("version and dischargedAt are required.");
        }
        if (cmd.deferBilling() && !mayDeferBilling) {
            throw new AccessDeniedException("Discharging with deferred billing needs discharge:defer-billing.");
        }
        Instant at = cmd.dischargedAt().truncatedTo(ChronoUnit.MICROS);
        return admissionService.underBedLock(org, id, held -> null, () -> { }, (row, held) -> {
            if (row.version() != cmd.version()) {
                throw AdmissionService.concurrentModification();
            }
            if (!AdmissionStatus.DISCHARGE_INITIATED.name().equals(row.status()) || held == null) {
                throw new ConflictException("INVALID_STATE_TRANSITION",
                        "Only an admission in DISCHARGE_INITIATED can be discharged.");
            }
            Instant now = admissionService.now();
            if (at.isBefore(row.admittedAt()) || at.isBefore(held.assignedAt())) {
                throw AdmissionService.invalid("dischargedAt must not be before the admission or its current bed.");
            }
            if (at.isAfter(now.plus(CLOCK_SKEW))) {
                throw AdmissionService.invalid("dischargedAt must not be in the future.");
            }
            if (summaries.findByAdmission(org, id).isEmpty()) {
                throw new BusinessRuleException("BUSINESS_RULE_VIOLATION", "The discharge summary is missing.");
            }
            Optional<FinalInvoice> fin = invoices.liveFinalInvoice(org, id);
            if (!cmd.deferBilling()) {
                if (cmd.invoiceId() == null || fin.isEmpty() || !fin.get().id().equals(cmd.invoiceId())
                        || !fin.get().issued()) {
                    throw new BusinessRuleException("BUSINESS_RULE_VIOLATION",
                            "An issued final invoice of this admission is required, or defer billing.");
                }
            }
            // The FINAL charges up to the planned discharge: the bed must be released at exactly that instant.
            if (fin.isPresent() && !at.equals(row.plannedDischargeAt())) {
                throw new BusinessRuleException("BUSINESS_RULE_VIOLATION",
                        "dischargedAt must equal the planned discharge the final invoice was charged to.");
            }
            beds.release(org, id, at);
            if (!admissions.discharge(org, id, row.version(), at, now)) {
                throw AdmissionService.concurrentModification();
            }
            audit.record(new AuditEntry(org, actor.userId(), "BED_RELEASED", AdmissionService.ENTITY, id,
                    AuditOutcome.SUCCESS, List.of("bedId", "releasedAt")));
            List<String> fields = new ArrayList<>(List.of("status", "dischargedAt"));
            if (cmd.deferBilling()) {
                fields.add("deferBilling");
            }
            audit.record(new AuditEntry(org, actor.userId(), "PATIENT_DISCHARGED", AdmissionService.ENTITY, id,
                    AuditOutcome.SUCCESS, fields));
            events.publishEvent(new PatientDischarged(org, id, row.patientId(), at));
            return new DischargeResult(AdmissionStatus.DISCHARGED.name(), true);
        });
    }

    private SummaryView view(UUID org, SummaryRow s, Instant planned) {
        List<Medication> meds = summaries.medications(org, s.id()).stream()
                .map(m -> new Medication(m.name(), m.dose(), m.frequency(), m.duration())).toList();
        return new SummaryView(s.id(), s.admissionId(), s.finalDiagnosis(), s.courseInHospital(), s.procedures(),
                s.conditionAtDischarge(), meds, s.followUp(), planned, s.version(), s.createdAt(), s.updatedAt());
    }

    private static SummaryRow validate(SummaryCommand cmd) {
        if (cmd.plannedDischargeAt() == null) {
            throw AdmissionService.invalid("plannedDischargeAt is required.");
        }
        String diagnosis = required(cmd.finalDiagnosis(), "finalDiagnosis", 2000);
        String condition = required(cmd.conditionAtDischarge(), "conditionAtDischarge", 2000);
        return new SummaryRow(null, null, diagnosis, optional(cmd.courseInHospital(), "courseInHospital", 10000),
                optional(cmd.procedures(), "procedures", 4000), condition,
                optional(cmd.followUp(), "followUp", 2000), 0, null, null);
    }

    private static List<MedicationRow> medications(List<Medication> list) {
        if (list == null) {
            return List.of();
        }
        if (list.size() > MAX_MEDICATIONS) {
            throw AdmissionService.invalid("At most " + MAX_MEDICATIONS + " medications.");
        }
        List<MedicationRow> rows = new ArrayList<>();
        for (Medication m : list) {
            if (m == null) {
                throw AdmissionService.invalid("medications must not contain null.");
            }
            rows.add(new MedicationRow(required(m.name(), "medications[].name", 200),
                    optional(m.dose(), "medications[].dose", 100),
                    optional(m.frequency(), "medications[].frequency", 100),
                    optional(m.duration(), "medications[].duration", 100)));
        }
        return rows;
    }

    private static String required(String value, String field, int max) {
        String text = optional(value, field, max);
        if (text == null) {
            throw AdmissionService.invalid(field + " is required.");
        }
        return text;
    }

    private static String optional(String value, String field, int max) {
        String text = value == null ? "" : value.strip();
        if (text.length() > max) {
            throw AdmissionService.invalid(field + " must be at most " + max + " characters.");
        }
        return text.isEmpty() ? null : text;
    }
}
