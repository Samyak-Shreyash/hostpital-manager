package com.hms.audit.infrastructure;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side of {@code hms.audit_event} for the viewer. The table is excluded from row-level security (append-only,
 * partitioned), so every query carries an explicit {@code organization_id} predicate. The time range goes on the
 * partition key {@code occurred_at}, which lets PostgreSQL prune partitions; ordering uses
 * {@code ix_audit_event_org_time}.
 */
@Repository
public class AuditEventQueries {

    /** Optional filters; {@code null} means "not filtered". */
    public record Filter(UUID actorId, String action, String entityType, UUID entityId, Instant from, Instant to) {
    }

    public record Row(UUID id, Instant occurredAt, UUID actorId, String action, String entityType, UUID entityId,
            String outcome, List<String> changedFields, String correlationId, List<ValueRow> changedValues) {
    }

    /** One element of {@code changed_values} (ADR-0016). */
    public record ValueRow(String field, String oldValue, String newValue) {
    }

    private final JdbcClient jdbc;

    AuditEventQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> find(UUID org, Filter f, int limit, long offset) {
        List<Object> params = new ArrayList<>();
        String where = where(org, f, params);
        params.add(limit);
        params.add(offset);
        return jdbc.sql("""
                SELECT id, occurred_at, actor_id, action, entity_type, entity_id, outcome, changed_fields,
                    correlation_id,
                    (SELECT array_agg(concat_ws(chr(31), e->>'field', coalesce(e->>'old', chr(30)),
                                coalesce(e->>'new', chr(30))) ORDER BY n)
                     FROM jsonb_array_elements(changed_values) WITH ORDINALITY AS v(e, n)) AS changed_values
                FROM hms.audit_event
                """ + where + " ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(params)
                .query((rs, n) -> {
                    Array fields = rs.getArray("changed_fields");
                    return new Row(rs.getObject("id", UUID.class),
                            rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("actor_id", UUID.class), rs.getString("action"),
                            rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                            rs.getString("outcome"),
                            fields == null ? List.of() : Arrays.asList((String[]) fields.getArray()),
                            rs.getString("correlation_id"), values(rs.getArray("changed_values")));
                }).list();
    }

    /**
     * Elements arrive as "field US old US new" (unit separator, chr 31; chr 30 stands for null), which cannot clash
     * with the code characters the column allows.
     */
    private static List<ValueRow> values(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        List<ValueRow> rows = new ArrayList<>();
        for (String element : (String[]) array.getArray()) {
            String[] parts = element.split("\u001f", -1);
            rows.add(new ValueRow(parts[0], nullable(parts[1]), nullable(parts[2])));
        }
        return rows;
    }

    private static String nullable(String value) {
        return "\u001e".equals(value) ? null : value;
    }

    public long count(UUID org, Filter f) {
        List<Object> params = new ArrayList<>();
        String where = where(org, f, params);
        return jdbc.sql("SELECT count(*) FROM hms.audit_event" + where).params(params).query(Long.class).single();
    }

    private static String where(UUID org, Filter f, List<Object> params) {
        StringBuilder sql = new StringBuilder(" WHERE organization_id = ?");
        params.add(org);
        if (f.actorId() != null) {
            sql.append(" AND actor_id = ?");
            params.add(f.actorId());
        }
        if (f.action() != null) {
            sql.append(" AND action = ?");
            params.add(f.action());
        }
        if (f.entityType() != null) {
            sql.append(" AND entity_type = ?");
            params.add(f.entityType());
        }
        if (f.entityId() != null) {
            sql.append(" AND entity_id = ?");
            params.add(f.entityId());
        }
        if (f.from() != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            sql.append(" AND occurred_at <= ?");
            params.add(Timestamp.from(f.to()));
        }
        return sql.toString();
    }
}
