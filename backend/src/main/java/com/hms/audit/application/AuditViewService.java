package com.hms.audit.application;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.audit.infrastructure.AuditEventQueries;
import com.hms.audit.infrastructure.AuditEventQueries.Filter;
import com.hms.audit.infrastructure.AuditEventQueries.Row;
import com.hms.shared.PageResult;
import com.hms.shared.domain.BusinessRuleException;
import com.hms.shared.tenancy.OrganizationPrincipal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit viewer (PRD AUD-*, api-design.md section 14). The organization is the principal's. Every query is itself
 * audited ({@code AUDIT_VIEWED}) in the same transaction, recording only the names of the filters used.
 */
@Service
public class AuditViewService {

    static final int MAX_PAGE_SIZE = 100;
    static final String VIEWED = "AUDIT_VIEWED";
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    /** Raw (unparsed) query parameters; blank means absent. */
    public record Query(String actorId, String action, String entityType, String entityId, String from, String to) {
    }

    public record AuditEventView(UUID id, Instant occurredAt, UUID actorId, String action, String entityType,
            UUID entityId, String outcome, List<String> changedFields, String correlationId) {
    }

    private final AuditEventQueries queries;
    private final AuditRecorder audit;

    AuditViewService(AuditEventQueries queries, AuditRecorder audit) {
        this.queries = queries;
        this.audit = audit;
    }

    /** Writes an audit event (the view itself), so not read-only. */
    @Transactional
    public PageResult<AuditEventView> list(OrganizationPrincipal actor, Query query, String pageText, String sizeText) {
        int page = number(pageText, "page");
        int size = number(sizeText, "size");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("page must be 0 or more and size 1 to " + MAX_PAGE_SIZE + ".");
        }
        List<String> used = new ArrayList<>();
        UUID actorId = uuid(query.actorId(), "actorId", used);
        String action = code(query.action(), "action", used);
        String entityType = code(query.entityType(), "entityType", used);
        UUID entityId = uuid(query.entityId(), "entityId", used);
        Instant from = instant(query.from(), "from", used);
        Instant to = instant(query.to(), "to", used);
        if (from != null && to != null && from.isAfter(to)) {
            throw invalid("from must not be after to.");
        }
        UUID org = actor.organizationId();
        Filter filter = new Filter(actorId, action, entityType, entityId, from, to);
        List<AuditEventView> items = queries.find(org, filter, size, (long) page * size).stream()
                .map(AuditViewService::view).toList();
        long total = queries.count(org, filter);
        audit.record(new AuditEntry(org, actor.userId(), VIEWED, "AUDIT_EVENT", null, AuditOutcome.SUCCESS, used));
        return new PageResult<>(items, page, size, total);
    }

    private static AuditEventView view(Row r) {
        return new AuditEventView(r.id(), r.occurredAt(), r.actorId(), r.action(), r.entityType(), r.entityId(),
                r.outcome(), r.changedFields(), r.correlationId());
    }

    private static UUID uuid(String value, String name, List<String> used) {
        if (blank(value)) {
            return null;
        }
        try {
            UUID id = UUID.fromString(value.trim());
            used.add(name);
            return id;
        } catch (IllegalArgumentException e) {
            throw invalid(name + " must be a UUID.");
        }
    }

    private static String code(String value, String name, List<String> used) {
        if (blank(value)) {
            return null;
        }
        if (!CODE.matcher(value.trim()).matches()) {
            throw invalid(name + " must be UPPER_SNAKE_CASE, 2-64 characters.");
        }
        used.add(name);
        return value.trim();
    }

    private static Instant instant(String value, String name, List<String> used) {
        if (blank(value)) {
            return null;
        }
        try {
            Instant parsed = Instant.parse(value.trim());
            used.add(name);
            return parsed;
        } catch (DateTimeParseException e) {
            throw invalid(name + " must be a UTC ISO-8601 instant, e.g. 2026-01-31T00:00:00Z.");
        }
    }

    private static int number(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw invalid(name + " must be a whole number.");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static BusinessRuleException invalid(String message) {
        return new BusinessRuleException("VALIDATION_ERROR", message);
    }
}
