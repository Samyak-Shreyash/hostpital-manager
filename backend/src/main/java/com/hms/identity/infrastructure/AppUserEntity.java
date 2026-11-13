package com.hms.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/** Row of {@code hms.app_user} (V3). Created by user management ([E1-7]); authentication updates lockout state. */
@Entity
@Table(name = "app_user")
public class AppUserEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(nullable = false)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Assigned ids: tells Spring Data to INSERT a created user instead of merging it. */
    @Transient
    private boolean newEntity;

    protected AppUserEntity() {
    }

    /** A new active user who must change the given (temporary) password at first login. */
    public static AppUserEntity create(UUID id, UUID organizationId, String email, String fullName,
            String passwordHash, Instant now) {
        AppUserEntity user = new AppUserEntity();
        user.id = id;
        user.organizationId = organizationId;
        user.email = email;
        user.fullName = fullName;
        user.passwordHash = passwordHash;
        user.status = "ACTIVE";
        user.mustChangePassword = true;
        user.createdAt = now;
        user.updatedAt = now;
        user.newEntity = true;
        return user;
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        this.newEntity = false;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    /** Sets a new password the user chose themselves: the first-login flag is cleared and the lockout reset. */
    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = newPasswordHash;
        this.mustChangePassword = false;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public void rename(String newFullName, Instant now) {
        this.fullName = newFullName;
        this.updatedAt = now;
    }

    /** Marks the row changed (e.g. its role set changed) so the version moves and clients see the change. */
    public void touch(Instant now) {
        this.updatedAt = now;
    }

    public void setActive(boolean active, Instant now) {
        this.status = active ? "ACTIVE" : "INACTIVE";
        this.updatedAt = now;
    }

    /** Administrator-set temporary password: the user must change it, and a lockout is cleared. */
    public void resetPassword(String temporaryPasswordHash, Instant now) {
        this.passwordHash = temporaryPasswordHash;
        this.mustChangePassword = true;
        this.failedLoginCount = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }
}
