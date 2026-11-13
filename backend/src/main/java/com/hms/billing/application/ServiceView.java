package com.hms.billing.application;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/** A catalog service as returned by the API; {@code currentPrice} is the price valid now, or null if none yet. */
public record ServiceView(UUID id, String code, String name, boolean active, Money currentPrice, long version,
        Instant createdAt, Instant updatedAt) {
}
