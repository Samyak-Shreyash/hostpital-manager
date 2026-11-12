package com.hms.audit.infrastructure;

import java.util.UUID;
import org.springframework.data.repository.Repository;

/** Insert-only: deliberately exposes no update or delete (invariant I8). Queries arrive with the viewer ([E1-9]). */
public interface AuditEventRepository extends Repository<AuditEventEntity, UUID> {

    AuditEventEntity save(AuditEventEntity event);
}
