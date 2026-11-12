package com.hms.billing.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Read-only view of the service catalog for other modules and for invoicing (no billing code uses it yet). */
public interface ServicePriceLookup {

    /**
     * The price of the service at {@code at}: the version with {@code effectiveFrom <= at < effectiveTo}.
     * Empty when the service does not exist in the organization, or has no price valid at that instant
     * (before its first version). The organization is explicit so system jobs can use it; rows of another
     * organization are never visible.
     */
    Optional<ServicePrice> priceAt(UUID organizationId, UUID serviceId, Instant at);
}
