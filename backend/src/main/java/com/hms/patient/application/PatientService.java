package com.hms.patient.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.DuplicateThresholds;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.patient.application.PatientView.AddressView;
import com.hms.patient.application.PatientView.ContactView;
import com.hms.patient.infrastructure.PatientRepository;
import com.hms.patient.infrastructure.PatientRepository.AbhaColumns;
import com.hms.patient.infrastructure.PatientRepository.PatientRow;
import com.hms.patient.infrastructure.PatientRepository.SearchFilter;
import com.hms.shared.PageResult;
import com.hms.shared.crypto.FieldCipher;
import com.hms.shared.crypto.LookupHasher;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.numbering.DocumentNumberAllocator;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patient registration and detail (PAT-1, PAT-2, BR-P2). The UHID comes from {@code document_sequence} under a row
 * lock (database-design.md section 6): it is allocated after validation and just before the first insert, and the
 * lock lasts until commit, so a failed registration returns its number and the sequence stays gapless.
 */
@Service
public class PatientService {

    static final String UHID = "UHID";
    static final String PERIOD_ALL = "ALL";
    static final int MAX_AGE_YEARS = 130;
    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_CANDIDATES = 10;
    static final int MIN_REASON = 5;
    static final int MAX_REASON = 500;
    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);

    private final PatientRepository repository;
    private final DocumentNumberAllocator numbers;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final FieldCipher cipher;
    private final LookupHasher abhaHasher;
    private final OrganizationDirectory organizations;

    public PatientService(PatientRepository repository, DocumentNumberAllocator numbers, AuditRecorder audit,
            UuidV7Generator ids, Clock clock, FieldCipher cipher, LookupHasher abhaHasher,
            OrganizationDirectory organizations) {
        this.organizations = organizations;
        this.cipher = cipher;
        this.abhaHasher = abhaHasher;
        this.repository = repository;
        this.numbers = numbers;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Registers a patient (PAT-1) after the duplicate check (BR-P3). Suspected duplicates are {@code 409
     * DUPLICATE_SUSPECTED} with masked candidates unless the command confirms "not a duplicate" with a reason, which
     * needs {@code patient:override-duplicate} ({@code mayOverrideDuplicate}) and is recorded and audited.
     *
     * <p>Concurrency: the UHID counter row of the organization is locked <em>before</em> the duplicate query and held
     * to commit, so registrations of one organization run the check-and-insert one at a time. Under READ COMMITTED the
     * second of two simultaneous registrations of the same person waits for the first, then its query sees the first
     * one's committed row and it gets {@code DUPLICATE_SUSPECTED}. A fuzzy rule cannot be a unique constraint, so this
     * lock (taken anyway for the gapless UHID) is the guarantee.
     */
    @Transactional
    public PatientView register(OrganizationPrincipal actor, RegisterPatientCommand command,
            boolean mayOverrideDuplicate) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate dateOfBirth = resolveDateOfBirth(command, today);
        UUID org = actor.organizationId();
        UUID id = ids.next();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String fullName = command.fullName().trim();
        String phone = normalizePhone(command.phone());
        AbhaColumns abha = null;
        if (blankToNull(command.abhaNumber()) != null) {
            String digits = normalizeAbha(command.abhaNumber());
            FieldCipher.Encrypted encrypted = cipher.encrypt(digits, abhaContext(org, id));
            abha = new AbhaColumns(encrypted.value(), encrypted.keyId(), abhaHasher.hash(digits),
                    digits.substring(digits.length() - 4));
        }

        // Last step before the checks and writes: the counter row stays locked until this transaction commits,
        // which serializes the duplicate check with every other registration of the organization.
        String uhid = formatUhid(numbers.next(org, UHID, PERIOD_ALL));
        List<DuplicateCandidate> candidates = findCandidates(org, fullName, dateOfBirth, phone);
        String overrideReason = null;
        if (!candidates.isEmpty()) {
            if (!command.confirmNotDuplicate()) {
                // Rolls back, so the UHID is not consumed.
                throw new ConflictException("DUPLICATE_SUSPECTED",
                        "This patient may already be registered. Check the candidates or confirm it is a new person.",
                        Map.of("candidates", candidates));
            }
            if (!mayOverrideDuplicate) {
                throw new AccessDeniedException("Confirming a suspected duplicate needs patient:override-duplicate.");
            }
            overrideReason = blankToNull(command.overrideReason());
            if (overrideReason == null || overrideReason.length() < MIN_REASON
                    || overrideReason.length() > MAX_REASON) {
                throw new BusinessRuleException("VALIDATION_ERROR", "overrideReason (" + MIN_REASON + " to "
                        + MAX_REASON + " characters) is required to confirm a suspected duplicate.");
            }
        }
        if (!repository.insertPatient(id, org, uhid, fullName, command.sex(), dateOfBirth, command.dobEstimated(),
                phone, abha, actor.userId(), now)) {
            // Rolls back, so the UHID is not consumed. The message never echoes the number.
            throw new ConflictException("DUPLICATE_ABHA", "Another patient already has this ABHA number.");
        }
        List<String> fields = new ArrayList<>(List.of("fullName", "sex", "dateOfBirth", "dobEstimated", "phone"));
        if (abha != null) {
            fields.add("abhaNumber"); // field name only, never the value
        }
        if (command.address() != null) {
            RegisterPatientCommand.Address a = command.address();
            String line2 = blankToNull(a.line2());
            repository.insertAddress(ids.next(), org, id,
                    new AddressView(a.line1().trim(), line2, a.city().trim(), a.state().trim(), a.pincode()), now);
            fields.addAll(List.of("address.line1", "address.city", "address.state", "address.pincode"));
            if (line2 != null) {
                fields.add("address.line2");
            }
        }
        if (command.emergencyContact() != null) {
            RegisterPatientCommand.EmergencyContact c = command.emergencyContact();
            repository.insertEmergencyContact(ids.next(), org, id,
                    new ContactView(c.name().trim(), c.relationship().trim(), normalizePhone(c.phone())), now);
            fields.addAll(List.of("emergencyContact.name", "emergencyContact.relationship",
                    "emergencyContact.phone"));
        }
        audit.record(new AuditEntry(org, actor.userId(), "PATIENT_CREATED", "PATIENT", id, AuditOutcome.SUCCESS,
                fields));
        if (overrideReason != null) {
            repository.insertDuplicateOverride(ids.next(), org, id,
                    candidates.stream().map(DuplicateCandidate::id).toList(), overrideReason, actor.userId(), now);
            // Field names only: the reason is free text and stays in patient_duplicate_override.
            audit.record(new AuditEntry(org, actor.userId(), "PATIENT_DUPLICATE_OVERRIDDEN", "PATIENT", id,
                    AuditOutcome.SUCCESS, List.of("duplicateOverride.reason", "duplicateOverride.candidateIds")));
        }
        return load(org, id);
    }

    /**
     * Pre-registration duplicate lookup (BR-P3): the same rule and thresholds as {@link #register}. Audited as one
     * {@code PATIENT_DUPLICATE_CHECK} event with the candidate count; the query terms are PHI and are not audited.
     */
    @Transactional
    public List<DuplicateCandidate> checkDuplicates(OrganizationPrincipal actor, String fullName,
            LocalDate dateOfBirth, String phone) {
        UUID org = actor.organizationId();
        List<DuplicateCandidate> candidates = findCandidates(org, fullName.trim(), dateOfBirth,
                normalizePhone(phone));
        audit.record(new AuditEntry(org, actor.userId(), "PATIENT_DUPLICATE_CHECK", "PATIENT", null,
                AuditOutcome.SUCCESS, List.of(), candidates.size()));
        return candidates;
    }

    private List<DuplicateCandidate> findCandidates(UUID org, String fullName, LocalDate dateOfBirth, String phone) {
        DuplicateThresholds t = organizations.duplicateThresholds(org);
        return repository.findDuplicateCandidates(org, fullName, dateOfBirth, phone, t.dob(), t.phone(),
                MAX_CANDIDATES).stream()
                .map(c -> new DuplicateCandidate(c.id(), c.uhid(), c.fullName(), c.dateOfBirth(),
                        DuplicateCandidate.maskPhone(c.phone()),
                        BigDecimal.valueOf(c.score()).setScale(2, RoundingMode.HALF_UP)))
                .toList();
    }

    /** Detail view. Audited, so it writes: not read-only. Another organization's id is a plain 404. */
    @Transactional
    public PatientView get(OrganizationPrincipal actor, UUID id) {
        UUID org = actor.organizationId();
        PatientView view = load(org, id);
        audit.record(AuditEntry.of(org, actor.userId(), "PATIENT_VIEWED", "PATIENT", id, AuditOutcome.SUCCESS));
        return view;
    }

    /**
     * Search (PAT-4). Filters combine with AND; at least one is required. One {@code PATIENT_SEARCH} audit event
     * records the result count only (owner decision 2026-11-13): query terms are PHI and are never audited.
     * ABHA has its own POST endpoint ({@link #searchByAbha}) so the number never appears in a URL or access log.
     */
    @Transactional
    public PageResult<PatientSummary> search(OrganizationPrincipal actor, String q, String uhid, String phone,
            LocalDate dob, int page, int size) {
        checkPage(page, size);
        String name = blankToNull(q);
        if (name != null && name.length() < 3) {
            throw new BusinessRuleException("VALIDATION_ERROR", "q must be at least 3 characters.");
        }
        String uhidTerm = blankToNull(uhid) == null ? null : uhid.trim().toUpperCase(java.util.Locale.ROOT);
        String phoneTerm = null;
        if (blankToNull(phone) != null) {
            phoneTerm = normalizePhone(phone.replaceAll("[\\s-]", ""));
            if (!phoneTerm.matches("\\+[1-9][0-9]{7,14}")) {
                throw new BusinessRuleException("VALIDATION_ERROR", "phone is not a valid number.");
            }
        }
        if (name == null && uhidTerm == null && phoneTerm == null && dob == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Give at least one of q, uhid, phone or dob.");
        }
        return runSearch(actor, new SearchFilter(name, uhidTerm, phoneTerm, null, dob), page, size);
    }

    /**
     * Exact ABHA search (ADR-0009): matched on the keyed hash, organization-scoped, same response and the same single
     * {@code PATIENT_SEARCH} audit event (result count only) as {@link #search}. The number is never logged.
     */
    @Transactional
    public PageResult<PatientSummary> searchByAbha(OrganizationPrincipal actor, String abhaNumber, int page,
            int size) {
        checkPage(page, size);
        if (blankToNull(abhaNumber) == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "abhaNumber is required.");
        }
        byte[] hash = abhaHasher.hash(normalizeAbha(abhaNumber));
        return runSearch(actor, new SearchFilter(null, null, null, hash, null), page, size);
    }

    private static void checkPage(int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
    }

    private PageResult<PatientSummary> runSearch(OrganizationPrincipal actor, SearchFilter filter, int page,
            int size) {
        UUID org = actor.organizationId();
        List<PatientSummary> items = repository.search(org, filter, size, (long) page * size).stream()
                .map(p -> new PatientSummary(p.id(), p.uhid(), p.fullName(), p.sex(), p.dateOfBirth(), p.phone(),
                        p.abhaLast4(), p.status()))
                .toList();
        long total = repository.countSearch(org, filter);
        audit.record(new AuditEntry(org, actor.userId(), "PATIENT_SEARCH", "PATIENT", null, AuditOutcome.SUCCESS,
                List.of(), (int) Math.min(total, Integer.MAX_VALUE)));
        return new PageResult<>(items, page, size, total);
    }

    /** 14 digits, optionally grouped 2-4-4-4 with hyphens (format only; no checksum or ABDM verification). */
    static final String ABHA_FORMAT = "[0-9]{14}|[0-9]{2}-[0-9]{4}-[0-9]{4}-[0-9]{4}";

    /** Associated data binding an ABHA ciphertext to its field, organization and patient row. */
    static String[] abhaContext(UUID org, UUID patientId) {
        return new String[] {"patient.abha", org.toString(), patientId.toString()};
    }

    static String normalizeAbha(String abha) {
        String trimmed = abha.trim();
        if (!trimmed.matches(ABHA_FORMAT)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "abhaNumber must be 14 digits.");
        }
        return trimmed.replace("-", "");
    }

    static String formatUhid(long number) {
        return String.format("HMS-%06d", number);
    }

    private PatientView load(UUID org, UUID id) {
        PatientRow p = repository.findPatient(org, id).orElseThrow(() -> new NotFoundException("Patient not found."));
        return new PatientView(p.id(), p.uhid(), p.fullName(), p.sex(), p.dateOfBirth(), p.dobEstimated(), p.phone(),
                p.abhaLast4(), repository.findCurrentAddress(org, id).orElse(null),
                repository.findEmergencyContact(org, id).orElse(null), p.status(), p.version(), p.createdAt(),
                p.updatedAt());
    }

    private static LocalDate resolveDateOfBirth(RegisterPatientCommand c, LocalDate today) {
        if ((c.dateOfBirth() == null) == (c.ageYears() == null)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide either dateOfBirth or ageYears.");
        }
        if (c.dateOfBirth() != null) {
            if (c.dateOfBirth().isAfter(today) || c.dateOfBirth().isBefore(EARLIEST_BIRTH)) {
                throw new BusinessRuleException("VALIDATION_ERROR", "dateOfBirth is not a plausible date.");
            }
            return c.dateOfBirth();
        }
        if (!c.dobEstimated()) {
            throw new BusinessRuleException("VALIDATION_ERROR", "ageYears requires dobEstimated=true.");
        }
        if (c.ageYears() < 0 || c.ageYears() > MAX_AGE_YEARS) {
            throw new BusinessRuleException("VALIDATION_ERROR", "ageYears must be 0 to " + MAX_AGE_YEARS + ".");
        }
        return today.minusYears(c.ageYears());
    }

    /** E.164 as is; a bare 10-digit number is an Indian number (+91). */
    static String normalizePhone(String phone) {
        String trimmed = phone.trim();
        return trimmed.startsWith("+") ? trimmed : "+91" + trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
