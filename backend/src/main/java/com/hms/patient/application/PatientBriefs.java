package com.hms.patient.application;

import com.hms.patient.api.PatientDirectory;
import com.hms.patient.infrastructure.PatientRepository;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PatientDirectory}: UHID and name of patients by id, scoped to the organization. */
@Service
class PatientBriefs implements PatientDirectory {

    private final PatientRepository repository;

    PatientBriefs(PatientRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PatientBrief> briefs(UUID organizationId, Collection<UUID> patientIds) {
        return repository.findBriefs(organizationId, patientIds);
    }
}
