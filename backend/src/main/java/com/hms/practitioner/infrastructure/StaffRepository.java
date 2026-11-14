package com.hms.practitioner.infrastructure;

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

/** Staff records (V19). The table is under RLS; queries also filter by org. */
@Repository
public class StaffRepository {

    public record StaffRow(UUID id, UUID userId, String fullName, String staffType, String designation,
            UUID departmentId, String status, long version, Instant createdAt, Instant updatedAt) {
    }

    /** Null filters are ignored; {@code likePattern} is already lower-cased, escaped and wrapped in %. */
    public record ListFilter(String staffType, UUID departmentId, String likePattern, String status) {
    }

    private static final String COLUMNS = """
            s.id, s.user_id, s.full_name, s.staff_type, s.designation, s.department_id, s.status, s.version,
            s.created_at, s.updated_at""";

    private static final String FILTERED = """
             FROM hms.staff s
            WHERE s.organization_id = :org
              AND (CAST(:status AS text) IS NULL OR s.status = CAST(:status AS text))
              AND (CAST(:type AS text) IS NULL OR s.staff_type = CAST(:type AS text))
              AND (CAST(:department AS uuid) IS NULL OR s.department_id = CAST(:department AS uuid))
              AND (CAST(:like AS text) IS NULL OR lower(s.full_name) LIKE CAST(:like AS text) ESCAPE '\\')
            """;

    private final JdbcClient jdbc;

    StaffRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID org, UUID userId, String fullName, String staffType, String designation,
            UUID departmentId, UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.staff (id, organization_id, user_id, full_name, staff_type, designation,
                    department_id, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """).params(id, org, userId, fullName, staffType, designation, departmentId, createdBy,
                Timestamp.from(now), Timestamp.from(now)).update();
    }

    public Optional<StaffRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.staff s WHERE s.organization_id = ? AND s.id = ?")
                .params(org, id).query(StaffRepository::map).optional();
    }

    /** The row locked FOR UPDATE until commit; empty for an unknown or other-org id. */
    public Optional<StaffRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM hms.staff s WHERE s.organization_id = ? AND s.id = ? FOR UPDATE")
                .params(org, id).query(StaffRepository::map).optional();
    }

    public List<StaffRow> list(UUID org, ListFilter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + FILTERED
                        + " ORDER BY lower(s.full_name), s.id LIMIT :limit OFFSET :offset")
                .param("org", org).param("status", f.status(), Types.VARCHAR)
                .param("type", f.staffType(), Types.VARCHAR).param("department", f.departmentId(), Types.OTHER)
                .param("like", f.likePattern(), Types.VARCHAR)
                .param("limit", limit).param("offset", offset).query(StaffRepository::map).list();
    }

    public long count(UUID org, ListFilter f) {
        return jdbc.sql("SELECT count(*)" + FILTERED)
                .param("org", org).param("status", f.status(), Types.VARCHAR)
                .param("type", f.staffType(), Types.VARCHAR).param("department", f.departmentId(), Types.OTHER)
                .param("like", f.likePattern(), Types.VARCHAR)
                .query(Long.class).single();
    }

    /** @return false on a version mismatch */
    public boolean update(UUID org, UUID id, long expectedVersion, String fullName, String staffType,
            String designation, UUID departmentId, String status, Instant now) {
        return jdbc.sql("""
                UPDATE hms.staff
                SET full_name = ?, staff_type = ?, designation = ?, department_id = ?, status = ?,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(fullName, staffType, designation, departmentId, status, Timestamp.from(now), org, id,
                expectedVersion).update() == 1;
    }

    private static StaffRow map(ResultSet rs, int n) throws SQLException {
        return new StaffRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("full_name"), rs.getString("staff_type"), rs.getString("designation"),
                rs.getObject("department_id", UUID.class), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
