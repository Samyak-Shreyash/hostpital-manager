package com.hms.seed;

import java.util.UUID;

/** What one run created (rows that already existed are not counted). Counts only, no values. */
public record SeedSummary(UUID organizationId, boolean organizationCreated, int departments, int specialties,
        int services, int users, int doctors, int staff, int availabilityTemplates, int leaves, int patients,
        int bulkPatients, int allergies, int histories) {

    public boolean isNoOp() {
        return !organizationCreated && departments + specialties + services + users + doctors + staff
                + availabilityTemplates + leaves + patients + bulkPatients + allergies + histories == 0;
    }
}
