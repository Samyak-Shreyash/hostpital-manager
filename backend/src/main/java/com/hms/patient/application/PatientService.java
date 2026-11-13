package com.hms.patient.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
    private static final LocalDate EARLIEST_BIRTH = LocalDate.of(1900, 1, 1);

    private final PatientRepository repository;
    private final DocumentNumberAllocator numbers;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;
    private final FieldCipher cipher;
    private final LookupHasher abhaHasher;

    public PatientService(PatientRepository repository, DocumentNumberAllocator numbers, AuditRecorder audit,
            UuidV7Generator ids, Clock clock, FieldCipher cipher, LookupHasher abhaHasher) {
        this.cipher = cipher;
        this.abhaHasher = abhaHasher;
        this.repository = repository;
        this.numbers = numbers;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public PatientView register(OrganizationPrincipal actor, RegisterPatientCommand command) {
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
            FieldCipher.Encrypted encrypted = cipher.encrypt(digits);
            abha = new AbhaColumns(encrypted.value(), encrypted.keyId(), abhaHasher.hash(digits),
                    digits.substring(digits.length() - 4));
        }

        // Last step before the writes: the counter row stays locked until this transaction commits.
        String uhid = formatUhid(numbers.next(org, UHID, PERIOD_ALL));
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
        return load(org, id);
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
     * ABHA is an exact match on its keyed hash (ADR-0009); the number itself is never stored in clear or audited.
     */
    @Transactional
    public PageResult<PatientSummary> search(OrganizationPrincipal actor, String q, String uhid, String phone,
            String abha, LocalDate dob, int page, int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    "page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
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
        byte[] abhaHash = null;
        if (blankToNull(abha) != null) {
            if (!abha.trim().matches(ABHA_FORMAT)) {
                throw new BusinessRuleException("VALIDATION_ERROR", "abha must be 14 digits.");
            }
            abhaHash = abhaHasher.hash(normalizeAbha(abha));
        }
        if (name == null && uhidTerm == null && phoneTerm == null && abhaHash == null && dob == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Give at least one of q, uhid, phone, abha or dob.");
        }
        UUID org = actor.organizationId();
        SearchFilter filter = new SearchFilter(name, uhidTerm, phoneTerm, abhaHash, dob);
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
