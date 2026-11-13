package com.hms.patient.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A suspected duplicate shown before registration (BR-P3, api-design.md section 6): only what a registration clerk
 * needs to tell two people apart. The phone is masked to its country prefix and last 4 digits; no ABHA, no address.
 *
 * @param score name trigram similarity in [0, 1], 2 decimals
 */
public record DuplicateCandidate(UUID id, String uhid, String fullName, LocalDate dateOfBirth, String phoneMasked,
        BigDecimal score) {

    /** {@code +919800000001} becomes {@code +91******0001}. */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() < 8) {
            return "****";
        }
        return phone.substring(0, 3) + "*".repeat(phone.length() - 7) + phone.substring(phone.length() - 4);
    }
}
