package com.hms.billing.web;

import com.hms.shared.domain.Money;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** {@code amount} is a decimal string ("500.00"); {@code effectiveFrom} defaults to now. */
record AddPriceRequest(@NotNull Money amount, Instant effectiveFrom) {
}
