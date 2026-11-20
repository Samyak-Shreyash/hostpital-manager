package com.hms.clinical.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.clinical.application.PrescriptionView.AllergyWarning;
import com.hms.clinical.application.PrescriptionView.Item;
import com.hms.clinical.infrastructure.EncounterRepository;
import com.hms.clinical.infrastructure.EncounterRepository.EncounterRow;
import com.hms.clinical.infrastructure.PrescriptionRepository;
import com.hms.clinical.infrastructure.PrescriptionRepository.ItemRow;
import com.hms.clinical.infrastructure.PrescriptionRepository.NewPrescription;
import com.hms.clinical.infrastructure.PrescriptionRepository.PrescriptionRow;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationSummary;
import com.hms.patient.api.PatientAllergies;
import com.hms.patient.api.PatientAllergies.AllergyInfo;
import com.hms.patient.api.PatientDirectory;
import com.hms.patient.api.PatientDirectory.PatientBrief;
import com.hms.practitioner.api.PractitionerDirectory;
import com.hms.practitioner.api.PractitionerDirectory.PractitionerBrief;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prescriptions ([E6-3], OPD-7, BR-C3; api-design.md section 9). One live prescription (DRAFT or ISSUED) per
 * encounter, open or finalized. The treating practitioner of the encounter (or a holder of encounter:update-any)
 * writes; any holder of prescription:read reads. Every change locks the prescription row and decides on the locked
 * state and version. An ISSUED prescription is immutable (also in the database, V28); a correction is
 * {@link #revise}, which supersedes it with a new DRAFT version. Drug names, instructions and allergy substances are
 * PHI: audited by field name only, never by value, never logged.
 */
@Service
public class PrescriptionService {

    static final String ENTITY = "PRESCRIPTION";
    static final int MAX_ITEMS = 50;

    /** One requested item; every rule is checked here (422 with field errors), not by the JSON binder. */
    public record ItemInput(String drugName, String strength, String form, String route, String dose,
            String frequency, Integer durationDays, String instructions) {
    }

    private final PrescriptionRepository repository;
    private final EncounterRepository encounters;
    private final PatientAllergies allergies;
    private final PractitionerDirectory practitioners;
    private final OrganizationDirectory organizations;
    private final PatientDirectory patients;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public PrescriptionService(PrescriptionRepository repository, EncounterRepository encounters,
            PatientAllergies allergies, PractitionerDirectory practitioners, OrganizationDirectory organizations,
            PatientDirectory patients, AuditRecorder audit, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.encounters = encounters;
        this.allergies = allergies;
        this.practitioners = practitioners;
        this.organizations = organizations;
        this.patients = patients;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** A new empty DRAFT on an open or finalized encounter; a second live prescription is refused. */
    @Transactional
    public PrescriptionView createDraft(OrganizationPrincipal actor, UUID encounterId, boolean mayEditAny) {
        UUID org = actor.organizationId();
        EncounterRow encounter = encounters.lock(org, encounterId)
                .orElseThrow(() -> new NotFoundException("Encounter not found."));
        requireTreating(org, actor, encounter, mayEditAny);
        UUID id = ids.next();
        if (repository.liveExists(org, encounterId) || !repository.insert(new NewPrescription(id, org, encounterId,
                encounter.patientId(), 1, null, actor.userId(), clock.instant()))) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "The encounter already has a prescription; revise it instead.");
        }
        audit.record(new AuditEntry(org, actor.userId(), "PRESCRIPTION_CREATED", ENTITY, id, AuditOutcome.SUCCESS,
                List.of("encounterId", "patientId", "status")));
        return view(org, repository.find(org, id).orElseThrow());
    }

    /** Reading a prescription is audited (clinical record). */
    @Transactional
    public PrescriptionView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        PrescriptionRow row = repository.find(org, id).orElseThrow(PrescriptionService::notFound);
        audit.record(AuditEntry.of(org, actor.userId(), "PRESCRIPTION_VIEWED", ENTITY, id, AuditOutcome.SUCCESS));
        return view(org, row);
    }

    /** A printable prescription: its bytes and a business-key based file name. */
    public record PdfFile(byte[] bytes, String filename) {
    }

    /**
     * The PDF of an ISSUED or SUPERSEDED prescription ([E6-5]); a DRAFT is not printable. Every name printed comes
     * from the owning modules' api ports. Generating it is audited like reading it (clinical record, field names only).
     */
    @Transactional
    public PdfFile pdf(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        PrescriptionRow row = repository.find(org, id).orElseThrow(PrescriptionService::notFound);
        if ("DRAFT".equals(row.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION", "A draft prescription cannot be printed; issue it first.");
        }
        EncounterRow encounter = encounters.find(org, row.encounterId()).orElseThrow(PrescriptionService::notFound);
        OrganizationSummary organization = organizations.find(org).orElseThrow(() -> notFound());
        PatientBrief patient = patients.briefs(org, List.of(row.patientId())).get(row.patientId());
        PractitionerBrief doctor = practitioners.brief(org, encounter.practitionerId()).orElse(null);
        if (patient == null) {
            throw notFound();
        }
        ZoneId zone = ZoneId.of(organization.timezone());
        String date = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(zone)
                .format(row.issuedAt() != null ? row.issuedAt() : clock.instant());
        String registration = doctor == null ? null : doctor.registrationNo()
                + (doctor.registrationCouncil() == null ? "" : " (" + doctor.registrationCouncil() + ")");
        List<PrescriptionPdf.Line> lines = repository.items(org, id).stream().map(i -> new PrescriptionPdf.Line(
                i.drugName(), i.strength(), i.form(), i.route(), i.dose(), i.frequency(), i.durationDays(),
                i.instructions())).toList();
        byte[] bytes = PrescriptionPdf.render(new PrescriptionPdf.Content(organization.name(),
                organization.address(), patient.name(), patient.uhid(), doctor == null ? null : doctor.fullName(),
                registration, date, "SUPERSEDED".equals(row.status()), lines));
        audit.record(new AuditEntry(org, actor.userId(), "PRESCRIPTION_PDF_GENERATED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("status", "versionNo")));
        return new PdfFile(bytes, "prescription-" + patient.uhid() + "-v" + row.versionNo() + ".pdf");
    }

    /** Replaces the items of a DRAFT. */
    @Transactional
    public PrescriptionView replaceItems(OrganizationPrincipal actor, UUID id, Long version,
            List<ItemInput> inputs, boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        PrescriptionRow row = lockedForWrite(actor, id, mayEditAny);
        requireDraft(row);
        List<ItemRow> items = validate(inputs);
        requireCurrent(row, version);
        Instant now = clock.instant();
        repository.replaceItems(org, id, items, now);
        repository.touch(org, id, now);
        audit.record(new AuditEntry(org, actor.userId(), "PRESCRIPTION_ITEMS_UPDATED", ENTITY, id,
                AuditOutcome.SUCCESS, List.of("items")));
        return view(org, repository.find(org, id).orElseThrow());
    }

    /** DRAFT to ISSUED; allergy warnings must be acknowledged. */
    @Transactional
    public PrescriptionView issue(OrganizationPrincipal actor, UUID id, Long version, Boolean acknowledge,
            boolean mayEditAny) {
        UUID org = actor.organizationId();
        requireVersion(version);
        PrescriptionRow row = lockedForWrite(actor, id, mayEditAny);
        requireDraft(row);
        requireCurrent(row, version);
        List<ItemRow> items = repository.items(org, id);
        if (items.isEmpty()) {
            throw invalid("A prescription needs at least one item to be issued.");
        }
        boolean warned = !warnings(org, row.patientId(), items).isEmpty();
        boolean acknowledged = Boolean.TRUE.equals(acknowledge);
        if (warned && !acknowledged) {
            throw new BusinessRuleException("ALLERGY_NOT_ACKNOWLEDGED",
                    "The prescription matches an allergy of the patient; acknowledge the warnings to issue it.");
        }
        repository.markIssued(org, id, actor.userId(), clock.instant());
        List<String> fields = new ArrayList<>(List.of("status", "issuedAt", "issuedBy"));
        if (warned) {
            fields.add("acknowledgeAllergyWarnings");
        }
        audit.record(new AuditEntry(org, actor.userId(), "PRESCRIPTION_ISSUED", ENTITY, id, AuditOutcome.SUCCESS,
                fields));
        return view(org, repository.find(org, id).orElseThrow());
    }

    /** ISSUED becomes SUPERSEDED and a new DRAFT copy (version_no + 1) takes over; the old one stays readable. */
    @Transactional
    public PrescriptionView revise(OrganizationPrincipal actor, UUID id, boolean mayEditAny) {
        UUID org = actor.organizationId();
        PrescriptionRow old = lockedForWrite(actor, id, mayEditAny);
        if (!"ISSUED".equals(old.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION", "Only an issued prescription can be revised.");
        }
        Instant now = clock.instant();
        List<ItemRow> items = repository.items(org, id);
        repository.markSuperseded(org, id, now);
        UUID next = ids.next();
        if (!repository.insert(new NewPrescription(next, org, old.encounterId(), old.patientId(),
                old.versionNo() + 1, old.id(), actor.userId(), now))) {
            throw new ConflictException("INVALID_STATE_TRANSITION", "The encounter already has a draft revision.");
        }
        repository.replaceItems(org, next, items, now);
        audit.record(new AuditEntry(org, actor.userId(), "PRESCRIPTION_REVISED", ENTITY, next, AuditOutcome.SUCCESS,
                List.of("supersedesId", "versionNo", "status", "items")));
        return view(org, repository.find(org, next).orElseThrow());
    }

    // --- internals ---

    /** 404 (unknown or other-org id), then 403 for a caller who may not write; the row stays locked. */
    private PrescriptionRow lockedForWrite(OrganizationPrincipal actor, UUID id, boolean mayEditAny) {
        UUID org = actor.organizationId();
        PrescriptionRow row = repository.lock(org, id).orElseThrow(PrescriptionService::notFound);
        EncounterRow encounter = encounters.find(org, row.encounterId()).orElseThrow(PrescriptionService::notFound);
        requireTreating(org, actor, encounter, mayEditAny);
        return row;
    }

    private void requireTreating(UUID org, OrganizationPrincipal actor, EncounterRow encounter, boolean mayEditAny) {
        Optional<UUID> own = practitioners.activePractitionerOfUser(org, actor.userId());
        if (!mayEditAny && !own.map(encounter.practitionerId()::equals).orElse(false)) {
            throw new AccessDeniedException("Only the treating practitioner, or a holder of encounter:update-any,"
                    + " can write prescriptions of this encounter.");
        }
    }

    private static void requireDraft(PrescriptionRow row) {
        if (!"DRAFT".equals(row.status())) {
            throw new ConflictException("INVALID_STATE_TRANSITION",
                    "Only a draft prescription can be changed; revise an issued one.");
        }
    }

    private static void requireCurrent(PrescriptionRow row, long version) {
        if (row.version() != version) {
            throw new ConflictException("CONCURRENT_MODIFICATION",
                    "The resource was changed by someone else. Reload and try again.");
        }
    }

    private static void requireVersion(Long version) {
        if (version == null) {
            throw invalid("version is required.");
        }
    }

    /** BR-C3: drugName, dose, frequency and durationDays (1-365) are mandatory; field errors name the item. */
    private static List<ItemRow> validate(List<ItemInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_ITEMS) {
            throw fieldErrors(List.of(error("items", "SIZE", "items needs 1 to " + MAX_ITEMS + " entries.")));
        }
        List<Map<String, String>> errors = new ArrayList<>();
        List<ItemRow> rows = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ItemInput in = inputs.get(i);
            String p = "items[" + i + "].";
            if (in == null) {
                errors.add(error("items[" + i + "]", "REQUIRED", "An item must not be null."));
                continue;
            }
            String drug = text(in.drugName(), 200, p + "drugName", true, errors);
            String strength = text(in.strength(), 100, p + "strength", false, errors);
            String form = text(in.form(), 100, p + "form", false, errors);
            String route = text(in.route(), 100, p + "route", false, errors);
            String dose = text(in.dose(), 100, p + "dose", true, errors);
            String frequency = text(in.frequency(), 100, p + "frequency", true, errors);
            Integer days = in.durationDays();
            if (days == null) {
                errors.add(error(p + "durationDays", "REQUIRED", "durationDays is required."));
            } else if (days < 1 || days > 365) {
                errors.add(error(p + "durationDays", "RANGE", "durationDays must be 1 to 365."));
            }
            String instructions = text(in.instructions(), 1000, p + "instructions", false, errors);
            if (errors.isEmpty()) {
                rows.add(new ItemRow(drug, strength, form, route, dose, frequency, days, instructions));
            }
        }
        if (!errors.isEmpty()) {
            throw fieldErrors(errors);
        }
        return rows;
    }

    /** Trims; blank is null (or an error when required); over-long is an error. Never echoes the text. */
    private static String text(String value, int max, String field, boolean required,
            List<Map<String, String>> errors) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            if (required) {
                errors.add(error(field, "REQUIRED", field + " is required."));
            }
            return null;
        }
        if (stripped.length() > max) {
            errors.add(error(field, "SIZE", field + " must be at most " + max + " characters."));
        }
        return stripped;
    }

    private static Map<String, String> error(String field, String code, String message) {
        return Map.of("field", field, "code", code, "message", message);
    }

    private static BusinessRuleException fieldErrors(List<Map<String, String>> errors) {
        return new BusinessRuleException("VALIDATION_ERROR", "One or more fields are invalid.",
                Map.of("errors", errors));
    }

    /**
     * An active allergy warns when its substance and a drug name contain one another, ignoring case. One warning
     * per distinct substance, in the order the allergies were recorded.
     */
    private List<AllergyWarning> warnings(UUID org, UUID patientId, List<ItemRow> items) {
        Map<String, AllergyWarning> found = new LinkedHashMap<>();
        for (AllergyInfo allergy : allergies.activeAllergies(org, patientId)) {
            String substance = allergy.substance() == null ? "" : allergy.substance().strip().toLowerCase(Locale.ROOT);
            if (substance.isEmpty()) {
                continue;
            }
            for (ItemRow item : items) {
                String drug = item.drugName().toLowerCase(Locale.ROOT);
                if (drug.contains(substance) || substance.contains(drug)) {
                    found.putIfAbsent(substance, new AllergyWarning(allergy.substance(), allergy.severity()));
                    break;
                }
            }
        }
        return List.copyOf(found.values());
    }

    private PrescriptionView view(UUID org, PrescriptionRow r) {
        List<ItemRow> rows = repository.items(org, r.id());
        List<Item> items = rows.stream().map(i -> new Item(i.drugName(), i.strength(), i.form(), i.route(),
                i.dose(), i.frequency(), i.durationDays(), i.instructions())).toList();
        return new PrescriptionView(r.id(), r.encounterId(), r.patientId(), r.status(), r.versionNo(),
                r.supersedesId(), r.issuedAt(), r.issuedBy(), r.version(), items,
                warnings(org, r.patientId(), rows));
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Prescription not found.");
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
