package com.hms.organization.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrganizationView(UUID id, String name, String timezone, String gstin, String address,
        BigDecimal duplicateDobThreshold, BigDecimal duplicatePhoneThreshold, long version, Instant createdAt,
        Instant updatedAt) {
}
