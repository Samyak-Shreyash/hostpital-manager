package com.hms.practitioner.application;

import com.hms.practitioner.api.PractitionerDirectory;
import com.hms.practitioner.infrastructure.PractitionerRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link PractitionerDirectory}: the practitioner linked to a user. */
@Service
class PractitionerLinks implements PractitionerDirectory {

    private final PractitionerRepository repository;

    PractitionerLinks(PractitionerRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> activePractitionerOfUser(UUID organizationId, UUID userId) {
        return repository.findActiveIdByUser(organizationId, userId);
    }
}
