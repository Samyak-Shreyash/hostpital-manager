package com.hms.appointment.infrastructure;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appointments (V23). The table is under RLS; every query also filters by org. */
@Repository
public class AppointmentRepository {

    /** Appointments that hold the practitioner's time (walk-ins do not); must match the WHERE of ex_appointment_practitioner_overlap. */
    static final String HOLDS_TIME = "('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED')"
            + " AND type = 'SCHEDULED'";

    /** Optional filters as {@code (? IS NULL OR col = ?)} pairs; the parameters come from {@link #filterParams}. */
    private static final String WHERE = """
              AND (?::uuid IS NULL OR practitioner_id = ?::uuid)
             AND (?::uuid IS NULL OR patient_id = ?::uuid)
             AND (?::timestamptz IS NULL OR start_at >= ?::timestamptz)
             AND (?::timestamptz IS NULL OR start_at < ?::timestamptz)
             AND (?::text IS NULL OR status = ?::text)""";

    private static final String EXCLUSION_VIOLATION = "23P01";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String COLUMNS = """
            id, patient_id, practitioner_id, start_at, end_at, type, status, reason, outside_availability, version,
            created_at, updated_at, rescheduled_from_id, cancel_reason, cancelled_at, token_no, token_date""";

    public record AppointmentRow(UUID id, UUID patientId, UUID practitionerId, Instant startAt, Instant endAt,
            String type, String status, String reason, boolean outsideAvailability, long version, Instant createdAt,
            Instant updatedAt, UUID rescheduledFromId, String cancelReason, Instant cancelledAt, Integer tokenNo,
            LocalDate tokenDate) {
    }

    /** Optional list filters; {@code from}/{@code to} bound start_at as {@code [from, to)}. */
    public record ListFilter(UUID practitionerId, UUID patientId, Instant from, Instant to, String status) {
    }

    public record NewAppointment(UUID id, UUID org, UUID patientId, UUID practitionerId, Instant startAt,
            Instant endAt, String reason, boolean outsideAvailability, UUID createdBy, Instant now,
            UUID rescheduledFromId) {
    }

    /** A walk-in: type WALK_IN, born CHECKED_IN with its token; it holds no slot. */
    public record NewWalkIn(UUID id, UUID org, UUID patientId, UUID practitionerId, Instant startAt, Instant endAt,
            String reason, UUID createdBy, Instant now, int tokenNo, LocalDate tokenDate) {
    }

    /** One line of the live queue; the patient is described by the patient module. */
    public record QueueRow(UUID appointmentId, UUID patientId, int tokenNo, String status) {
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
                        reason, outside_availability, created_by, created_at, updated_at, rescheduled_from_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(a.id(), a.org(), a.patientId(), a.practitionerId(), Timestamp.from(a.startAt()),
                    Timestamp.from(a.endAt()), a.reason(), a.outsideAvailability(), a.createdBy(),
                    Timestamp.from(a.now()), Timestamp.from(a.now()), a.rescheduledFromId()).update();
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

    public InsertOutcome insertWalkIn(NewWalkIn a) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.appointment (id, organization_id, patient_id, practitioner_id, start_at, end_at,
                        type, status, reason, created_by, created_at, updated_at, token_no, token_date)
                    VALUES (?, ?, ?, ?, ?, ?, 'WALK_IN', 'CHECKED_IN', ?, ?, ?, ?, ?, ?)
                    """).params(a.id(), a.org(), a.patientId(), a.practitionerId(), Timestamp.from(a.startAt()),
                    Timestamp.from(a.endAt()), a.reason(), a.createdBy(), Timestamp.from(a.now()),
                    Timestamp.from(a.now()), a.tokenNo(), Date.valueOf(a.tokenDate())).update();
            return InsertOutcome.INSERTED;
        } catch (DataIntegrityViolationException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())
                        && String.valueOf(sql.getMessage()).contains("fk_appointment_patient")) {
                    return InsertOutcome.UNKNOWN_PATIENT;
                }
            }
            throw e;
        }
    }

    /**
     * The next token number of the doctor and day, starting at 1. The upsert's row lock is held until the caller's
     * transaction ends, so concurrent check-ins of one doctor and day queue up and numbers are gapless; allocate it
     * last in the transaction (the database-design.md section 6 pattern).
     */
    public int nextToken(UUID org, UUID practitionerId, LocalDate date) {
        return jdbc.sql("""
                INSERT INTO hms.queue_token_counter (organization_id, doctor_id, token_date, next_value)
                VALUES (?, ?, ?, 2)
                ON CONFLICT (organization_id, doctor_id, token_date)
                DO UPDATE SET next_value = hms.queue_token_counter.next_value + 1
                RETURNING next_value - 1
                """).params(org, practitionerId, Date.valueOf(date)).query(Integer.class).single();
    }

    /** CHECKED_IN with its token; the caller holds the row lock and has checked version and state. */
    public void checkIn(UUID org, UUID id, int tokenNo, LocalDate tokenDate, Instant now) {
        jdbc.sql("""
                UPDATE hms.appointment SET status = 'CHECKED_IN', token_no = ?, token_date = ?, version = version + 1,
                    updated_at = ? WHERE organization_id = ? AND id = ?
                """).params(tokenNo, Date.valueOf(tokenDate), Timestamp.from(now), org, id).update();
    }

    /** Patients still in the doctor's queue that day: checked in or in consultation, by token. */
    public List<QueueRow> queue(UUID org, UUID practitionerId, LocalDate date) {
        return jdbc.sql("""
                SELECT id, patient_id, token_no, status FROM hms.appointment
                WHERE organization_id = ? AND practitioner_id = ? AND token_date = ? AND token_no IS NOT NULL
                    AND status IN ('CHECKED_IN', 'IN_CONSULTATION')
                ORDER BY token_no
                """).params(org, practitionerId, Date.valueOf(date))
                .query((rs, n) -> new QueueRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                        rs.getInt("token_no"), rs.getString("status"))).list();
    }

    public Optional<AppointmentRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.appointment WHERE organization_id = ? AND id = ?")
                .params(org, id).query(AppointmentRepository::map).optional();
    }

    /** The row, locked until the transaction ends: state changes are decided on the locked state. */
    public Optional<AppointmentRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.appointment WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(AppointmentRepository::map).optional();
    }

    /** Sets the new status and bumps the version; the caller holds the row lock and has checked the version. */
    public void transition(UUID org, UUID id, String status, String cancelReason, Instant cancelledAt, Instant now) {
        jdbc.sql("""
                UPDATE hms.appointment SET status = ?, cancel_reason = ?, cancelled_at = ?, version = version + 1,
                    updated_at = ? WHERE organization_id = ? AND id = ?
                """).params(status, cancelReason, cancelledAt == null ? null : Timestamp.from(cancelledAt),
                Timestamp.from(now), org, id).update();
    }

    public List<AppointmentRow> list(UUID org, ListFilter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.appointment WHERE organization_id = ?" + WHERE
                        + " ORDER BY start_at, id LIMIT ? OFFSET ?")
                .params(filterParams(org, f, limit, offset)).query(AppointmentRepository::map).list();
    }

    public long count(UUID org, ListFilter f) {
        return jdbc.sql("SELECT count(*) FROM hms.appointment WHERE organization_id = ?" + WHERE)
                .params(filterParams(org, f, null, null)).query(Long.class).single();
    }

    private static List<Object> filterParams(UUID org, ListFilter f, Integer limit, Long offset) {
        List<Object> p = new java.util.ArrayList<>();
        p.add(org);
        p.add(f.practitionerId());
        p.add(f.practitionerId());
        p.add(f.patientId());
        p.add(f.patientId());
        p.add(f.from() == null ? null : Timestamp.from(f.from()));
        p.add(f.from() == null ? null : Timestamp.from(f.from()));
        p.add(f.to() == null ? null : Timestamp.from(f.to()));
        p.add(f.to() == null ? null : Timestamp.from(f.to()));
        p.add(f.status());
        p.add(f.status());
        if (limit != null) {
            p.add(limit);
            p.add(offset);
        }
        return p;
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
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("rescheduled_from_id", UUID.class), rs.getString("cancel_reason"),
                rs.getTimestamp("cancelled_at") == null ? null : rs.getTimestamp("cancelled_at").toInstant(),
                rs.getObject("token_no", Integer.class), rs.getObject("token_date", LocalDate.class));
    }
}
