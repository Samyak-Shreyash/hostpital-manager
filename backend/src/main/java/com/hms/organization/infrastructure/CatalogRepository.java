package com.hms.organization.infrastructure;

import com.hms.organization.application.CatalogKind;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Departments and specialties. Both tables are under RLS (V8) and every query also filters by the principal's
 * organization. The table name comes only from {@link CatalogKind} (a fixed enum), never from request input.
 */
@Repository
public class CatalogRepository {

    public record Row(UUID id, String code, String name, boolean active, long version, Instant createdAt,
            Instant updatedAt) {
    }

    private static final String COLUMNS = "id, code, name, active, version, created_at, updated_at";
    private static final String ACTIVE_FILTER =
            " WHERE organization_id = :org AND (CAST(:active AS boolean) IS NULL OR active = :active)";

    private final JdbcClient jdbc;

    CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the code already exists in the organization (the unique index decides, so races are safe). */
    public boolean insertIfAbsent(CatalogKind kind, UUID id, UUID org, String code, String name, Instant now) {
        return jdbc.sql("INSERT INTO hms." + kind.table() + """
                 (id, organization_id, code, name, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, ?, ?)
                ON CONFLICT (organization_id, code) DO NOTHING
                """).params(id, org, code, name, Timestamp.from(now), Timestamp.from(now)).update() == 1;
    }

    public Optional<Row> find(CatalogKind kind, UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms." + kind.table() + " WHERE organization_id = ? AND id = ?")
                .params(org, id).query(CatalogRepository::map).optional();
    }

    public List<Row> list(CatalogKind kind, UUID org, Boolean active, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms." + kind.table() + ACTIVE_FILTER
                        + " ORDER BY code LIMIT :limit OFFSET :offset")
                .param("org", org).param("active", active, Types.BOOLEAN).param("limit", limit)
                .param("offset", offset).query(CatalogRepository::map).list();
    }

    public long count(CatalogKind kind, UUID org, Boolean active) {
        return jdbc.sql("SELECT count(*) FROM hms." + kind.table() + ACTIVE_FILTER)
                .param("org", org).param("active", active, Types.BOOLEAN).query(Long.class).single();
    }

    /**
     * Active flag per id for the given ids of the organization, share-locked until commit (an entry cannot be
     * deactivated while it is being assigned). Unknown and other-org ids are absent from the result.
     */
    public java.util.Map<UUID, Boolean> activeFlags(CatalogKind kind, UUID org, java.util.Collection<UUID> ids) {
        java.util.Map<UUID, Boolean> result = new java.util.HashMap<>();
        if (ids.isEmpty()) {
            return result;
        }
        jdbc.sql("SELECT id, active FROM hms." + kind.table()
                        + " WHERE organization_id = ? AND id = ANY(?) ORDER BY id FOR SHARE")
                .params(org, ids.toArray(new UUID[0]))
                .query((rs, n) -> result.put(rs.getObject("id", UUID.class), rs.getBoolean("active"))).list();
        return result;
    }

    /** Compare-and-set on {@code version}: false when the row changed since the caller read it. */
    public boolean update(CatalogKind kind, UUID org, UUID id, long expectedVersion, String name, boolean active,
            Instant now) {
        return jdbc.sql("UPDATE hms." + kind.table() + """
                 SET name = ?, active = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(name, active, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    private static Row map(ResultSet rs, int n) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("active"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
