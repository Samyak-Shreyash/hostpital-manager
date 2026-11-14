package com.hms.practitioner.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Weekly windows and leave exceptions (V20). Both tables are under RLS; every query also filters by org. */
@Repository
public class AvailabilityRepository {

    public record WindowRow(int weekday, LocalTime start, LocalTime end, int slotMinutes) {
    }

    public record ExceptionRow(UUID id, UUID practitionerId, LocalDate date, LocalTime start, LocalTime end,
            String status, long version, Instant createdAt, Instant updatedAt) {
    }

    private static final String EXCEPTION_COLUMNS = """
            id, practitioner_id, leave_date, start_time, end_time, status, version, created_at, updated_at""";

    private final JdbcClient jdbc;

    AvailabilityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<WindowRow> windows(UUID org, UUID practitionerId) {
        return jdbc.sql("""
                SELECT weekday, start_time, end_time, slot_minutes FROM hms.practitioner_availability
                WHERE organization_id = ? AND practitioner_id = ? ORDER BY weekday, start_time
                """).params(org, practitionerId)
                .query((rs, n) -> new WindowRow(rs.getInt("weekday"), rs.getObject("start_time", LocalTime.class),
                        rs.getObject("end_time", LocalTime.class), rs.getInt("slot_minutes"))).list();
    }

    /** Replaces the weekly template. The caller holds the practitioner row lock. */
    public void replaceWindows(UUID org, UUID practitionerId, List<UUID> ids, List<WindowRow> windows, Instant now) {
        jdbc.sql("DELETE FROM hms.practitioner_availability WHERE organization_id = ? AND practitioner_id = ?")
                .params(org, practitionerId).update();
        for (int i = 0; i < windows.size(); i++) {
            WindowRow w = windows.get(i);
            jdbc.sql("""
                    INSERT INTO hms.practitioner_availability (id, organization_id, practitioner_id, weekday,
                        start_time, end_time, slot_minutes, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(ids.get(i), org, practitionerId, w.weekday(), Time.valueOf(w.start()),
                    Time.valueOf(w.end()), w.slotMinutes(), Timestamp.from(now)).update();
        }
    }

    public void insertException(UUID id, UUID org, UUID practitionerId, LocalDate date, LocalTime start,
            LocalTime end, UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.availability_exception (id, organization_id, practitioner_id, leave_date,
                    start_time, end_time, status, created_by, created_at, updated_at)
                VALUES (:id, :org, :practitioner, :date, :start, :end, 'ACTIVE', :by, :now, :now)
                """).param("id", id).param("org", org).param("practitioner", practitionerId)
                .param("date", Date.valueOf(date))
                .param("start", start == null ? null : Time.valueOf(start), Types.TIME)
                .param("end", end == null ? null : Time.valueOf(end), Types.TIME)
                .param("by", createdBy).param("now", Timestamp.from(now)).update();
    }

    public Optional<ExceptionRow> exception(UUID org, UUID practitionerId, UUID id) {
        return jdbc.sql("SELECT " + EXCEPTION_COLUMNS + """
                 FROM hms.availability_exception
                WHERE organization_id = ? AND practitioner_id = ? AND id = ?""")
                .params(org, practitionerId, id).query(AvailabilityRepository::map).optional();
    }

    /** Exceptions on {@code from..to} (null bounds are open), any status, ordered by date and time. */
    public List<ExceptionRow> exceptions(UUID org, UUID practitionerId, LocalDate from, LocalDate to,
            boolean activeOnly, int limit) {
        return jdbc.sql("SELECT " + EXCEPTION_COLUMNS + """
                 FROM hms.availability_exception
                WHERE organization_id = :org AND practitioner_id = :practitioner
                  AND (CAST(:from AS date) IS NULL OR leave_date >= CAST(:from AS date))
                  AND (CAST(:to AS date) IS NULL OR leave_date <= CAST(:to AS date))
                  AND (NOT :activeOnly OR status = 'ACTIVE')
                ORDER BY leave_date, start_time NULLS FIRST, id LIMIT :limit""")
                .param("org", org).param("practitioner", practitionerId)
                .param("from", from == null ? null : Date.valueOf(from), Types.DATE)
                .param("to", to == null ? null : Date.valueOf(to), Types.DATE)
                .param("activeOnly", activeOnly).param("limit", limit)
                .query(AvailabilityRepository::map).list();
    }

    /** ACTIVE -> CANCELLED, guarded by the expected version. @return false on a version mismatch or not active */
    public boolean cancel(UUID org, UUID id, long expectedVersion, UUID cancelledBy, Instant now) {
        return jdbc.sql("""
                UPDATE hms.availability_exception
                SET status = 'CANCELLED', cancelled_by = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ? AND status = 'ACTIVE'
                """).params(cancelledBy, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    private static ExceptionRow map(ResultSet rs, int n) throws SQLException {
        return new ExceptionRow(rs.getObject("id", UUID.class), rs.getObject("practitioner_id", UUID.class),
                rs.getObject("leave_date", LocalDate.class), rs.getObject("start_time", LocalTime.class),
                rs.getObject("end_time", LocalTime.class), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
