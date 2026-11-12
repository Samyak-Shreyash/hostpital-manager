package com.hms.billing.api;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.util.UUID;

/**
 * One price version of a catalog service. Valid for {@code effectiveFrom <= t < effectiveTo} (half-open);
 * {@code effectiveTo} is {@code null} while the version is open-ended. Billing copies {@code amount} (and may
 * record {@code id}) onto an invoice line: the copy never changes when the catalog price does.
 */
public record ServicePrice(UUID id, UUID serviceId, Money amount, Instant effectiveFrom, Instant effectiveTo) {
}
