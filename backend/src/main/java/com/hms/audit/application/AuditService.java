package com.hms.audit.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditRecorder;
import com.hms.audit.api.AuditValueChange;
import com.hms.audit.infrastructure.AuditEventEntity;
import com.hms.audit.infrastructure.AuditEventRepository;
import com.hms.shared.id.UuidV7Generator;
import com.hms.shared.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link AuditRecorder} backed by {@code hms.audit_event}. {@code MANDATORY} joins the caller's transaction and
 * refuses to run without one, so an audit write never commits separately from the change it records.
 */
@Service
class AuditService implements AuditRecorder {

    private final AuditEventRepository repository;
    private final UuidV7Generator ids;
    private final Clock clock;

    AuditService(AuditEventRepository repository, UuidV7Generator ids, Clock clock) {
        this.repository = repository;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        repository.save(new AuditEventEntity(
                ids.next(),
                entry.organizationId(),
                clock.instant(),
                entry.actorId(),
                entry.action(),
                entry.entityType(),
                entry.entityId(),
                entry.outcome().name(),
                MDC.get(CorrelationIdFilter.MDC_KEY),
                entry.changedFields().toArray(String[]::new),
                entry.resultCount(),
                json(entry.valueChanges())));
    }

    /**
     * Serialized by hand: field names and values are restricted to code characters by {@link AuditValueChange}
     * and {@link AuditEntry}, so they never need escaping.
     */
    static String json(List<AuditValueChange> changes) {
        return changes.stream()
                .map(c -> "{\"field\":\"" + c.field() + "\",\"old\":" + quoted(c.oldValue()) + ",\"new\":"
                        + quoted(c.newValue()) + "}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
