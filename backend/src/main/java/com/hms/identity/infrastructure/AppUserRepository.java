package com.hms.identity.infrastructure;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AppUserRepository extends JpaRepository<AppUserEntity, UUID> {

    /** Row-locked so concurrent failed attempts cannot lose an increment and slip past the lockout. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUserEntity u where u.email = :email")
    Optional<AppUserEntity> findByEmailForUpdate(String email);

    /** User management: the organization comes from the principal, so another org's id is simply absent (404). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUserEntity u where u.id = :id and u.organizationId = :organizationId")
    Optional<AppUserEntity> findByIdAndOrganizationForUpdate(UUID id, UUID organizationId);

    @Query("select u from AppUserEntity u where u.id = :id and u.organizationId = :organizationId")
    Optional<AppUserEntity> findByIdAndOrganization(UUID id, UUID organizationId);

    boolean existsByEmail(String email);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from AppUserEntity u where u.id = :id")
    Optional<AppUserEntity> findByIdForUpdate(UUID id);
}
