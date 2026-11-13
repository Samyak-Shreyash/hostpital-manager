package com.hms.billing.application;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** One price version as returned by the API. */
public record PriceView(UUID id, UUID serviceId, Money amount, Instant effectiveFrom, Instant effectiveTo,
        Instant createdAt) {
}
