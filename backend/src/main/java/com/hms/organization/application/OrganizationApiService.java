package com.hms.organization.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.organization.api.DuplicateThresholds;
import com.hms.organization.api.OrganizationDirectory;
import com.hms.organization.api.OrganizationProvisioning;
import com.hms.organization.api.OrganizationSummary;
import com.hms.organization.api.OrganizationRules;
import com.hms.organization.infrastructure.OrganizationRepository;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.id.UuidV7Generator;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements organization's public api for other modules. */
@Service
class OrganizationApiService implements OrganizationDirectory, OrganizationProvisioning {

    private final OrganizationRepository organizations;
    private final AuditRecorder audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    OrganizationApiService(OrganizationRepository organizations, AuditRecorder audit, UuidV7Generator ids,
            Clock clock) {
        this.organizations = organizations;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrganizationSummary> find(UUID organizationId) {
        return organizations.find(organizationId)
                .map(r -> new OrganizationSummary(r.id(), r.name(), r.timezone()));
    }

    @Override
    @Transactional(readOnly = true)
    public DuplicateThresholds duplicateThresholds(UUID organizationId) {
        return organizations.find(organizationId)
                .map(r -> new DuplicateThresholds(r.duplicateDobThreshold(), r.duplicatePhoneThreshold()))
                .orElse(new DuplicateThresholds(DuplicateThresholds.DEFAULT_DOB, DuplicateThresholds.DEFAULT_PHONE));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean anyOrganizationExists() {
        return organizations.anyExists();
    }

    @Override
    @Transactional
    public UUID create(String name, String timezone) {
        if (name == null || name.isBlank() || name.trim().length() > 200) {
            throw new BusinessRuleException("VALIDATION_ERROR", "Organization name must be 1 to 200 characters.");
        }
        if (!OrganizationRules.isIanaTimezone(timezone)) {
            throw new BusinessRuleException("VALIDATION_ERROR", "timezone must be a valid IANA time zone id.");
        }
        UUID id = ids.next();
        organizations.insert(id, name.trim(), timezone, clock.instant());
        audit.record(new AuditEntry(id, null, "ORGANIZATION_CREATED", OrganizationSettingsService.ENTITY, id,
                AuditOutcome.SUCCESS, List.of("name", "timezone")));
        return id;
    }
}
