package com.hms.appointment.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appointments (V23). The table is under RLS; every query also filters by org. */
@Repository
public class AppointmentRepository {

    /** Statuses that hold the practitioner's time; must match the WHERE of ex_appointment_practitioner_overlap. */
    static final String HOLDS_TIME = "('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED')";

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String COLUMNS = """
            id, patient_id, practitioner_id, start_at, end_at, type, status, reason, outside_availability, version,
            created_at, updated_at""";

    public record AppointmentRow(UUID id, UUID patientId, UUID practitionerId, Instant startAt, Instant endAt,
            String type, String status, String reason, boolean outsideAvailability, long version, Instant createdAt,
            Instant updatedAt) {
    }

    public record NewAppointment(UUID id, UUID org, UUID patientId, UUID practitionerId, Instant startAt,
            Instant endAt, String reason, boolean outsideAvailability, UUID createdBy, Instant now) {
    }

    /** What an insert did; the database, not the caller's checks, decides under concurrency. */
    public enum InsertOutcome { INSERTED, OVERLAP, UNKNOWN_PATIENT }

    private final JdbcClient jdbc;

    AppointmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Serializes bookings of one practitioner until the transaction ends. Concurrent overlapping inserts would
     * otherwise wait on each other's exclusion check and can deadlock; queued, each sees the committed winner and
     * fails cleanly with an overlap. The practitioner row belongs to another module, hence an advisory lock.
     */
    public void lockSchedule(UUID practitionerId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('appointment_schedule:' || ?::text, 0))")
                .param(practitionerId).query().singleValue();
    }

    public InsertOutcome insert(NewAppointment a) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                        reason, outside_availability, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(a.id(), a.org(), a.patientId(), a.practitionerId(), Timestamp.from(a.startAt()),
                    Timestamp.from(a.endAt()), a.reason(), a.outsideAvailability(), a.createdBy(),
                    Timestamp.from(a.now()), Timestamp.from(a.now())).update();
            return InsertOutcome.INSERTED;
        } catch (DataIntegrityViolationException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                    return InsertOutcome.OVERLAP;
                }
                if (t instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())
                        && String.valueOf(sql.getMessage()).contains("fk_appointment_patient")) {
                    return InsertOutcome.UNKNOWN_PATIENT;
                }
            }
            throw e;
        }
    }

    public Optional<AppointmentRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.appointment WHERE organization_id = ? AND id = ?")
                .params(org, id).query(AppointmentRepository::map).optional();
    }

    /** Intervals that hold the practitioner's time and overlap {@code [from, to)}. */
    public List<Instant[]> booked(UUID org, UUID practitionerId, Instant from, Instant to) {
        return jdbc.sql("SELECT start_at, end_at FROM hms.appointment WHERE organization_id = ? AND practitioner_id = ?"
                        + " AND status IN " + HOLDS_TIME + " AND start_at < ? AND end_at > ?")
                .params(org, practitionerId, Timestamp.from(to), Timestamp.from(from))
                .query((rs, n) -> new Instant[] {rs.getTimestamp("start_at").toInstant(),
                        rs.getTimestamp("end_at").toInstant()})
                .list();
    }

    private static AppointmentRow map(ResultSet rs, int n) throws SQLException {
        return new AppointmentRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("practitioner_id", UUID.class), rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(), rs.getString("type"), rs.getString("status"),
                rs.getString("reason"), rs.getBoolean("outside_availability"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
