package com.hms.audit.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

/** Row of the append-only {@code hms.audit_event}. Insert-only: Hibernate never issues UPDATE for it. */
@Entity
@Immutable
@Table(name = "audit_event")
public class AuditEventEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private UUID entityId;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "correlation_id")
    private String correlationId;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "changed_fields", nullable = false)
    private String[] changedFields;

    @Transient
    private boolean isNew = true;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(UUID id, UUID organizationId, Instant occurredAt, UUID actorId, String action,
            String entityType, UUID entityId, String outcome, String correlationId, String[] changedFields) {
        this.id = id;
        this.organizationId = organizationId;
        this.occurredAt = occurredAt;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.outcome = outcome;
        this.correlationId = correlationId;
        this.changedFields = changedFields;
    }

    @Override
    public UUID getId() {
        return id;
    }

    /** Ids are assigned (UUIDv7), so Spring Data must INSERT rather than SELECT-then-merge. */
    @Override
    public boolean isNew() {
        return isNew;
    }
}
