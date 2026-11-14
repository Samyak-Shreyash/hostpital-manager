package com.hms.organization.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.DuplicateThresholds;
import com.hms.organization.api.OrganizationRules;
import com.hms.organization.infrastructure.OrganizationRepository;
import com.hms.organization.infrastructure.OrganizationRepository.Row;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.domain.ConflictException;
import com.hms.shared.domain.NotFoundException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings of the caller's own organization (api-design.md section 4). The organization is always the principal's;
 * there is no id in the URL. {@code organization} is outside RLS, so it is scoped explicitly here.
 */
@Service
public class OrganizationSettingsService {

    static final String ENTITY = "ORGANIZATION";

    private final OrganizationRepository organizations;
    private final AuditRecorder audit;
    private final Clock clock;

    public OrganizationSettingsService(OrganizationRepository organizations, AuditRecorder audit, Clock clock) {
        this.organizations = organizations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OrganizationView get(OrganizationPrincipal actor) {
        return view(load(actor.organizationId()));
    }

    /**
     * Partial update; {@code version} must match. An empty {@code gstin} or {@code address} clears it, {@code null}
     * leaves it alone. Audited with field names only.
     */
    @Transactional
    public OrganizationView update(OrganizationPrincipal actor, long version, String name, String timezone,
            String gstin, String address, BigDecimal duplicateDobThreshold, BigDecimal duplicatePhoneThreshold) {
        if (name == null && timezone == null && gstin == null && address == null && duplicateDobThreshold == null
                && duplicatePhoneThreshold == null) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Provide at least one of name, timezone, address, "
                    + "gstin, duplicateDobThreshold, duplicatePhoneThreshold.");
        }
        UUID org = actor.organizationId();
        Row current = load(org);
        if (current.version() != version) {
            throw concurrentModification();
        }
        List<String> changed = new ArrayList<>();
        String newName = current.name();
        if (name != null && !name.trim().equals(current.name())) {
            newName = name.trim();
            changed.add("name");
        }
        String newTimezone = current.timezone();
        if (timezone != null && !timezone.equals(current.timezone())) {
            if (!OrganizationRules.isIanaTimezone(timezone)) {
                throw new BusinessRuleException("VALIDATION_ERROR", "timezone must be a valid IANA time zone id.");
            }
            newTimezone = timezone;
            changed.add("timezone");
        }
        String newGstin = current.gstin();
        if (gstin != null) {
            String wanted = gstin.trim().isEmpty() ? null : gstin.trim();
            if (wanted != null && !OrganizationRules.isGstin(wanted)) {
                throw new BusinessRuleException("VALIDATION_ERROR", "gstin is not a valid GSTIN.");
            }
            if (!Objects.equals(wanted, current.gstin())) {
                newGstin = wanted;
                changed.add("gstin");
            }
        }
        String newAddress = current.address();
        if (address != null) {
            String wanted = address.trim().isEmpty() ? null : address.trim();
            if (!Objects.equals(wanted, current.address())) {
                newAddress = wanted;
                changed.add("address");
            }
        }
        BigDecimal newDob = threshold(duplicateDobThreshold, current.duplicateDobThreshold(), "duplicateDobThreshold",
                changed);
        BigDecimal newPhone = threshold(duplicatePhoneThreshold, current.duplicatePhoneThreshold(),
                "duplicatePhoneThreshold", changed);
        if (changed.isEmpty()) {
            return view(current);
        }
        if (!organizations.update(org, version, newName, newTimezone, newGstin, newAddress, newDob, newPhone,
                clock.instant())) {
            throw concurrentModification();
        }
        audit.record(new AuditEntry(org, actor.userId(), "ORGANIZATION_UPDATED", ENTITY, org, AuditOutcome.SUCCESS,
                changed));
        return view(load(org));
    }

    /** A duplicate threshold (BR-P3): in (0, 1], at most 2 decimals; {@code null} keeps the current value. */
    private static BigDecimal threshold(BigDecimal wanted, BigDecimal current, String field, List<String> changed) {
        if (wanted == null) {
            return current;
        }
        if (!DuplicateThresholds.isValid(wanted)) {
            throw new BusinessRuleException("VALIDATION_ERROR",
                    field + " must be greater than 0 and at most 1, with at most 2 decimals.");
        }
        if (wanted.compareTo(current) == 0) {
            return current;
        }
        changed.add(field);
        return wanted;
    }

    private Row load(UUID org) {
        return organizations.find(org).orElseThrow(() -> new NotFoundException("Organization not found."));
    }

    private static ConflictException concurrentModification() {
        return new ConflictException("CONCURRENT_MODIFICATION",
                "The resource was changed by someone else. Reload and try again.");
    }

    private static OrganizationView view(Row r) {
        return new OrganizationView(r.id(), r.name(), r.timezone(), r.gstin(), r.address(), r.duplicateDobThreshold(),
                r.duplicatePhoneThreshold(), r.version(),
                r.createdAt(), r.updatedAt());
    }
}
