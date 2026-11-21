package com.hms.bedmanagement.infrastructure;

import com.hms.shared.domain.BusinessRuleException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Wards (V36). Under RLS; every query also filters by organization. */
@Repository
public class WardRepository {

    public record WardRow(UUID id, String code, String name, UUID departmentId, String status, String careType,
            long version, Instant createdAt, Instant updatedAt) {
    }

    private static final String COLUMNS = "id, code, name, department_id, status, care_type, version, created_at, updated_at";
    private static final String FK_VIOLATION = "23503";

    private final JdbcClient jdbc;

    WardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the code exists in the organization (the unique index decides, so races are safe). */
    public boolean insert(UUID id, UUID org, String code, String name, UUID departmentId, String careType,
            Instant now) {
        try {
            return jdbc.sql("""
                    INSERT INTO hms.ward (id, organization_id, code, name, department_id, status, care_type, created_at,
                        updated_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                    ON CONFLICT (organization_id, code) DO NOTHING
                    """).params(id, org, code, name, departmentId, careType, Timestamp.from(now),
                    Timestamp.from(now))
                    .update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    public Optional<WardRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.ward WHERE organization_id = ? AND id = ?")
                .params(org, id).query(WardRepository::map).optional();
    }

    public List<WardRow> list(UUID org, String status, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM hms.ward WHERE organization_id = :org AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY code LIMIT :limit OFFSET :offset
                """).param("org", org).param("status", status, Types.VARCHAR).param("limit", limit)
                .param("offset", offset).query(WardRepository::map).list();
    }

    public long count(UUID org, String status) {
        return jdbc.sql("""
                SELECT count(*) FROM hms.ward
                WHERE organization_id = :org AND (CAST(:status AS text) IS NULL OR status = :status)
                """).param("org", org).param("status", status, Types.VARCHAR).query(Long.class).single();
    }

    /** Wards shown on the availability board: every ACTIVE ward, or just the one asked for. */
    public List<WardRow> forAvailability(UUID org, UUID wardId) {
        if (wardId != null) {
            return find(org, wardId).stream().toList();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.ward WHERE organization_id = ? AND status = 'ACTIVE' "
                + "ORDER BY code").params(org).query(WardRepository::map).list();
    }

    /** Compare-and-set on {@code version}: false when the row changed since the caller read it. */
    public boolean update(UUID org, UUID id, long expectedVersion, String name, UUID departmentId, String status,
            String careType, Instant now) {
        try {
            return jdbc.sql("""
                    UPDATE hms.ward SET name = ?, department_id = ?, status = ?, care_type = ?, version = version + 1, updated_at = ?
                    WHERE organization_id = ? AND id = ? AND version = ?
                    """).params(name, departmentId, status, careType, Timestamp.from(now), org, id, expectedVersion)
                    .update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && FK_VIOLATION.equals(sql.getSQLState())) {
                return new BusinessRuleException("VALIDATION_ERROR", "departmentId does not exist.");
            }
        }
        return e;
    }

    private static WardRow map(ResultSet rs, int n) throws SQLException {
        return new WardRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getObject("department_id", UUID.class), rs.getString("status"), rs.getString("care_type"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
