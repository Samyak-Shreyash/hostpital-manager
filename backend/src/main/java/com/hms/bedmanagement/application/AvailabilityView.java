package com.hms.bedmanagement.application;

import java.util.List;
import java.util.UUID;

/**
 * Bed availability ([E8-1]): per ward counters plus one entry per bed.
 * {@code total = occupied + available + outOfService}.
 */
public record AvailabilityView(List<WardAvailability> wards) {

    public record WardRef(UUID id, String name, String careType) {
    }

    public record WardAvailability(WardRef ward, String code, String status, int total, int occupied, int available,
            int outOfService, List<BedAvailability> beds) {
    }

    /** {@code admissionId} is the admission holding the bed, or null when it is free. */
    public record BedAvailability(UUID id, String bedNo, String bedClass, WardRef ward, String status,
            boolean occupied, UUID admissionId) {
    }
}
