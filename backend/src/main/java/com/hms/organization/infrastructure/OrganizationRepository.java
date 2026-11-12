package com.hms.organization.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code hms.organization} is outside RLS (database-design.md section 14), so callers always pass the organization
 * of the authenticated principal explicitly; nothing here lists or looks up organizations by request input.
 */
@Repository
public class OrganizationRepository {

    public record Row(UUID id, String name, String timezone, String gstin, String address, String status, long version,
            Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    OrganizationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(UUID id) {
        return jdbc.sql("""
                SELECT id, name, timezone, gstin, address, status, version, created_at, updated_at
                FROM hms.organization WHERE id = ?
                """).param(id)
                .query((rs, n) -> new Row(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getString("timezone"), rs.getString("gstin"), rs.getString("address"),
                        rs.getString("status"), rs.getLong("version"),
                        rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    public boolean anyExists() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM hms.organization)").query(Boolean.class).single();
    }

    public void insert(UUID id, String name, String timezone, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.organization (id, name, timezone, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?, ?)
                """).params(id, name, timezone, Timestamp.from(now), Timestamp.from(now)).update();
    }

    /** Compare-and-set on {@code version}: false when the row changed since the caller read it. */
    public boolean update(UUID id, long expectedVersion, String name, String timezone, String gstin, String address,
            Instant now) {
        return jdbc.sql("""
                UPDATE hms.organization
                SET name = ?, timezone = ?, gstin = ?, address = ?, version = version + 1, updated_at = ?
                WHERE id = ? AND version = ?
                """).params(name, timezone, gstin, address, Timestamp.from(now), id, expectedVersion)
                .update() == 1;
    }
}
