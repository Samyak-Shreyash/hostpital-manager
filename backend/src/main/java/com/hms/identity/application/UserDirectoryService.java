package com.hms.identity.application;

import com.hms.identity.api.UserDirectory;
import com.hms.identity.infrastructure.UserAdminRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements identity's {@link UserDirectory} for other modules. */
@Service
class UserDirectoryService implements UserDirectory {

    private final UserAdminRepository users;

    UserDirectoryService(UserAdminRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public Optional<UserSummary> findForLink(UUID organizationId, UUID userId) {
        return users.findForShare(organizationId, userId)
                .map(r -> new UserSummary(r.id(), r.fullName(), "ACTIVE".equals(r.status())));
    }
}
