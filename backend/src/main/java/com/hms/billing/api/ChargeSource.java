package com.hms.billing.api;

import com.hms.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Charges another module supplies to billing (hld.md section 4: admission implements this port; billing never
 * depends on admission). Methods run in the caller's transaction, scoped to the organization; nothing is audited
 * here (the caller audits its own use case).
 */
public interface ChargeSource {

    /** The admission, or empty when unknown in the organization. */
    Optional<AdmissionInfo> admission(UUID organizationId, UUID admissionId);

    /**
     * Room-day lines (BR-B6 rule [A], ruling R2) for the admission's bed-days up to {@code endAt}, skipping dates on
     * or before {@code afterDate} (null: from the admission date). One line per bed, consecutive date run and unit
     * price. A charged date with no valid bed price is {@code 422 BUSINESS_RULE_VIOLATION} naming the bed. An
     * unknown admission is {@code 404}.
     */
    List<ChargeLine> roomCharges(UUID organizationId, UUID admissionId, LocalDate afterDate, Instant endAt);

    /** {@code chargeEndAt}: discharged instant, else the planned discharge instant, else null. */
    record AdmissionInfo(UUID id, UUID patientId, String status, Instant admittedAt, Instant chargeEndAt) {
    }

    record ChargeLine(UUID serviceId, String description, int quantity, Money unitPrice, LocalDate from,
            LocalDate to) {
    }
}
