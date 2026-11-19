package com.hms.patient.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only lookup of the few identifying fields other modules show next to a patient (the OPD queue board). Ids
 * that are unknown or belong to another organization are simply absent from the result.
 */
public interface PatientDirectory {

    Map<UUID, PatientBrief> briefs(UUID organizationId, Collection<UUID> patientIds);

    /** UHID and name only; no contact details or clinical data. */
    record PatientBrief(UUID id, String uhid, String name) {
    }
}
