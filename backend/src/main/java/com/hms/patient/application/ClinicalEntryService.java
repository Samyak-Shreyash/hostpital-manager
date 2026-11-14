package com.hms.patient.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.patient.application.ClinicalEntryViews.AllergyView;
import com.hms.patient.application.ClinicalEntryViews.HistoryView;
import com.hms.patient.infrastructure.ClinicalEntryRepository;
import com.hms.patient.infrastructure.ClinicalEntryRepository.EntryRow;
import com.hms.patient.infrastructure.PatientRepository;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Allergies and medical history (PAT-3, [E3-4]). Entries are append-only and never deleted; a wrong entry is marked
 * {@code ENTERED_IN_ERROR} once. Content is clinical free text: audit records field names and entry ids only.
 */
@Service
public class ClinicalEntryService {

    static final Set<String> SEVERITIES = Set.of("MILD", "MODERATE", "SEVERE");
    static final int MAX_SUBJECT = 200;
    static final int MAX_DETAIL = 1000;

    private final ClinicalEntryRepository entries;
    private final PatientRepository patients;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public ClinicalEntryService(ClinicalEntryRepository entries, PatientRepository patients, AuditRecorder audit,
            UuidV7Generator ids, Clock clock) {
        this.entries = entries;
        this.patients = patients;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public AllergyView addAllergy(OrganizationPrincipal actor, UUID patientId, String substance, String reaction,
            String severity) {
        requirePatient(actor.organizationId(), patientId);
        if (severity == null || !SEVERITIES.contains(severity)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "severity must be one of MILD, MODERATE, SEVERE.");
        }
        UUID id = ids.next();
        entries.insert(id, actor.organizationId(), patientId, ClinicalEntryRepository.ALLERGY,
                text(substance, "substance", MAX_SUBJECT, true), text(reaction, "reaction", MAX_DETAIL, false),
                severity, actor.userId(), now());
        audit.record(new AuditEntry(actor.organizationId(), actor.userId(), "PATIENT_ALLERGY_ADDED",
                "PATIENT_ALLERGY", id, AuditOutcome.SUCCESS, List.of("substance", "reaction", "severity")));
        return allergy(entries.find(actor.organizationId(), patientId, ClinicalEntryRepository.ALLERGY, id)
                .orElseThrow());
    }

    @Transactional
    public HistoryView addHistory(OrganizationPrincipal actor, UUID patientId, String condition, String notes) {
        requirePatient(actor.organizationId(), patientId);
        UUID id = ids.next();
        entries.insert(id, actor.organizationId(), patientId, ClinicalEntryRepository.HISTORY,
                text(condition, "condition", MAX_SUBJECT, true), text(notes, "notes", MAX_DETAIL, false), null,
                actor.userId(), now());
        audit.record(new AuditEntry(actor.organizationId(), actor.userId(), "PATIENT_HISTORY_ADDED",
                "PATIENT_HISTORY", id, AuditOutcome.SUCCESS, List.of("condition", "notes")));
        return history(entries.find(actor.organizationId(), patientId, ClinicalEntryRepository.HISTORY, id)
                .orElseThrow());
    }

    @Transactional
    public List<AllergyView> listAllergies(OrganizationPrincipal actor, UUID patientId) {
        requirePatient(actor.organizationId(), patientId);
        audit.record(AuditEntry.of(actor.organizationId(), actor.userId(), "PATIENT_ALLERGIES_VIEWED", "PATIENT",
                patientId, AuditOutcome.SUCCESS));
        return entries.list(actor.organizationId(), patientId, ClinicalEntryRepository.ALLERGY).stream()
                .map(ClinicalEntryService::allergy).toList();
    }

    @Transactional
    public List<HistoryView> listHistory(OrganizationPrincipal actor, UUID patientId) {
        requirePatient(actor.organizationId(), patientId);
        audit.record(AuditEntry.of(actor.organizationId(), actor.userId(), "PATIENT_HISTORY_VIEWED", "PATIENT",
                patientId, AuditOutcome.SUCCESS));
        return entries.list(actor.organizationId(), patientId, ClinicalEntryRepository.HISTORY).stream()
                .map(ClinicalEntryService::history).toList();
    }

    /** Marks an allergy entered in error; a second call is {@code 409 ENTRY_ALREADY_ENTERED_IN_ERROR}. */
    @Transactional
    public AllergyView markAllergyInError(OrganizationPrincipal actor, UUID patientId, UUID entryId) {
        return allergy(markInError(actor, patientId, ClinicalEntryRepository.ALLERGY, entryId,
                "PATIENT_ALLERGY_MARKED_IN_ERROR", "PATIENT_ALLERGY"));
    }

    @Transactional
    public HistoryView markHistoryInError(OrganizationPrincipal actor, UUID patientId, UUID entryId) {
        return history(markInError(actor, patientId, ClinicalEntryRepository.HISTORY, entryId,
                "PATIENT_HISTORY_MARKED_IN_ERROR", "PATIENT_HISTORY"));
    }

    private EntryRow markInError(OrganizationPrincipal actor, UUID patientId, String kind, UUID entryId,
            String action, String entityType) {
        UUID org = actor.organizationId();
        requirePatient(org, patientId);
        EntryRow current = entries.lock(org, patientId, kind, entryId)
                .orElseThrow(() -> new NotFoundException("Entry not found."));
        if (!ClinicalEntryRepository.ACTIVE.equals(current.status())) {
            throw new ConflictException("ENTRY_ALREADY_ENTERED_IN_ERROR",
                    "This entry is already marked as entered in error.");
        }
        entries.markEnteredInError(org, entryId, actor.userId(), now());
        audit.record(new AuditEntry(org, actor.userId(), action, entityType, entryId, AuditOutcome.SUCCESS,
                List.of("status")));
        return entries.find(org, patientId, kind, entryId).orElseThrow();
    }

    private void requirePatient(UUID org, UUID patientId) {
        patients.findPatient(org, patientId).orElseThrow(() -> new NotFoundException("Patient not found."));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Trims; blank is null when optional. The message names the field, never the text. */
    private static String text(String value, String field, int max, boolean required) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (required) {
                throw new BusinessRuleException("VALIDATION_ERROR", field + " is required.");
            }
            return null;
        }
        if (trimmed.length() > max) {
            throw new BusinessRuleException("VALIDATION_ERROR", field + " must be at most " + max + " characters.");
        }
        return trimmed;
    }

    private static AllergyView allergy(EntryRow r) {
        return new AllergyView(r.id(), r.subject(), r.detail(), r.severity(), r.status(), r.createdAt(),
                r.enteredInErrorAt());
    }

    private static HistoryView history(EntryRow r) {
        return new HistoryView(r.id(), r.subject(), r.detail(), r.status(), r.createdAt(), r.enteredInErrorAt());
    }
}
