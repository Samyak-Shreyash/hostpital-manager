package com.hms.clinical.infrastructure;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Encounters and their parts (V26). Tables are under RLS; every query also filters by org. */
@Repository
public class EncounterRepository {

    private static final String COLUMNS = """
            id, patient_id, practitioner_id, appointment_id, status, chief_complaint, version, started_at,
            finalized_at, created_at, updated_at""";

    public record EncounterRow(UUID id, UUID patientId, UUID practitionerId, UUID appointmentId, String status,
            String chiefComplaint, long version, Instant startedAt, Instant finalizedAt, Instant createdAt,
            Instant updatedAt) {
    }

    public record NewEncounter(UUID id, UUID org, UUID patientId, UUID practitionerId, UUID appointmentId,
            String chiefComplaint, UUID createdBy, Instant now) {
    }

    public record ObservationRow(String kind, BigDecimal value, String unit) {
    }

    public record NoteRow(String kind, String text, UUID authorId, Instant createdAt) {
    }

    public record DiagnosisRow(String code, String description, boolean primary) {
    }

    public record FollowUpRow(LocalDate date, String instructions) {
    }

    private final JdbcClient jdbc;

    EncounterRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when the appointment already has an encounter (the unique constraint decides) */
    public boolean insert(NewEncounter e) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.encounter (id, organization_id, patient_id, practitioner_id, appointment_id,
                        chief_complaint, started_at, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(e.id(), e.org(), e.patientId(), e.practitionerId(), e.appointmentId(),
                    e.chiefComplaint(), Timestamp.from(e.now()), e.createdBy(), Timestamp.from(e.now()),
                    Timestamp.from(e.now())).update();
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    public Optional<EncounterRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.encounter WHERE organization_id = ? AND id = ?")
                .params(org, id).query(EncounterRepository::map).optional();
    }

    /** The row locked until the transaction ends: edits are decided on the locked state and version. */
    public Optional<EncounterRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM hms.encounter WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(EncounterRepository::map).optional();
    }

    public List<EncounterRow> listByPatient(UUID org, UUID patientId, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.encounter WHERE organization_id = ? AND patient_id = ?"
                        + " ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(org, patientId, limit, offset).query(EncounterRepository::map).list();
    }

    public long countByPatient(UUID org, UUID patientId) {
        return jdbc.sql("SELECT count(*) FROM hms.encounter WHERE organization_id = ? AND patient_id = ?")
                .params(org, patientId).query(Long.class).single();
    }

    /** OPEN to FINALIZED on the locked row (the V27 guard allows exactly this update), bumping the version. */
    public void finalizeEncounter(UUID org, UUID id, UUID by, Instant now) {
        jdbc.sql("UPDATE hms.encounter SET status = 'FINALIZED', finalized_at = ?, finalized_by = ?,"
                        + " version = version + 1, updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Timestamp.from(now), by, Timestamp.from(now), org, id).update();
    }

    /** Appends an addendum note; addenda accumulate and are never replaced. */
    public void addAddendum(UUID org, UUID encounterId, UUID noteId, String text, UUID author, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.clinical_note (id, organization_id, encounter_id, kind, text, author_id, created_at)
                VALUES (?, ?, ?, 'ADDENDUM', ?, ?, ?)
                """).params(noteId, org, encounterId, text, author, Timestamp.from(now)).update();
    }

    /** Bumps the version of the locked row. */
    public void touch(UUID org, UUID id, Instant now) {
        jdbc.sql("UPDATE hms.encounter SET version = version + 1, updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Timestamp.from(now), org, id).update();
    }

    // --- observations ---

    public void replaceObservations(UUID org, UUID encounterId, List<ObservationRow> rows, UUID by, Instant now) {
        jdbc.sql("UPDATE hms.encounter_observation SET replaced_at = ? WHERE encounter_id = ? AND replaced_at IS NULL")
                .params(Timestamp.from(now), encounterId).update();
        for (ObservationRow r : rows) {
            jdbc.sql("""
                    INSERT INTO hms.encounter_observation (id, organization_id, encounter_id, kind, value, unit,
                        recorded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(UUID.randomUUID(), org, encounterId, r.kind(), r.value(), r.unit(), by,
                    Timestamp.from(now)).update();
        }
    }

    public List<ObservationRow> observations(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT kind, value, unit FROM hms.encounter_observation WHERE organization_id = ?"
                        + " AND encounter_id = ? AND replaced_at IS NULL ORDER BY kind")
                .params(org, encounterId).query((rs, n) -> new ObservationRow(rs.getString("kind"),
                        rs.getBigDecimal("value"), rs.getString("unit"))).list();
    }

    // --- notes ---

    public void replaceNote(UUID org, UUID encounterId, UUID noteId, String kind, String text, UUID author,
            Instant now) {
        jdbc.sql("UPDATE hms.clinical_note SET replaced_at = ? WHERE encounter_id = ? AND kind = ?"
                        + " AND replaced_at IS NULL").params(Timestamp.from(now), encounterId, kind).update();
        jdbc.sql("""
                INSERT INTO hms.clinical_note (id, organization_id, encounter_id, kind, text, author_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(noteId, org, encounterId, kind, text, author, Timestamp.from(now)).update();
    }

    /** Current notes (and later addenda) in time order. */
    public List<NoteRow> notes(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT kind, text, author_id, created_at FROM hms.clinical_note WHERE organization_id = ?"
                        + " AND encounter_id = ? AND replaced_at IS NULL ORDER BY created_at, id")
                .params(org, encounterId).query((rs, n) -> new NoteRow(rs.getString("kind"), rs.getString("text"),
                        rs.getObject("author_id", UUID.class), rs.getTimestamp("created_at").toInstant())).list();
    }

    // --- diagnoses ---

    public void replaceDiagnoses(UUID org, UUID encounterId, List<DiagnosisRow> rows, UUID by, Instant now) {
        jdbc.sql("UPDATE hms.encounter_diagnosis SET replaced_at = ? WHERE encounter_id = ? AND replaced_at IS NULL")
                .params(Timestamp.from(now), encounterId).update();
        int position = 0;
        for (DiagnosisRow r : rows) {
            jdbc.sql("""
                    INSERT INTO hms.encounter_diagnosis (id, organization_id, encounter_id, code, description,
                        is_primary, position, recorded_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(UUID.randomUUID(), org, encounterId, r.code(), r.description(), r.primary(),
                    position++, by, Timestamp.from(now)).update();
        }
    }

    public List<DiagnosisRow> diagnoses(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT code, description, is_primary FROM hms.encounter_diagnosis WHERE organization_id = ?"
                        + " AND encounter_id = ? AND replaced_at IS NULL ORDER BY position")
                .params(org, encounterId).query((rs, n) -> new DiagnosisRow(rs.getString("code"),
                        rs.getString("description"), rs.getBoolean("is_primary"))).list();
    }

    // --- plan and follow-up ---

    public void upsertPlan(UUID org, UUID encounterId, String text, UUID by, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.treatment_plan (organization_id, encounter_id, text, updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (encounter_id) DO UPDATE SET text = EXCLUDED.text, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """).params(org, encounterId, text, by, Timestamp.from(now)).update();
    }

    public Optional<String> plan(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT text FROM hms.treatment_plan WHERE organization_id = ? AND encounter_id = ?")
                .params(org, encounterId).query(String.class).optional();
    }

    public void upsertFollowUp(UUID org, UUID encounterId, LocalDate date, String instructions, UUID by,
            Instant now) {
        jdbc.sql("""
                INSERT INTO hms.follow_up (organization_id, encounter_id, follow_up_date, instructions, updated_by,
                    updated_at) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (encounter_id) DO UPDATE SET follow_up_date = EXCLUDED.follow_up_date,
                    instructions = EXCLUDED.instructions, updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """).params(org, encounterId, Date.valueOf(date), instructions, by, Timestamp.from(now)).update();
    }

    public Optional<FollowUpRow> followUp(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT follow_up_date, instructions FROM hms.follow_up WHERE organization_id = ?"
                        + " AND encounter_id = ?").params(org, encounterId)
                .query((rs, n) -> new FollowUpRow(rs.getDate("follow_up_date").toLocalDate(),
                        rs.getString("instructions"))).optional();
    }

    private static EncounterRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp finalized = rs.getTimestamp("finalized_at");
        return new EncounterRow(rs.getObject("id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getObject("practitioner_id", UUID.class), rs.getObject("appointment_id", UUID.class),
                rs.getString("status"), rs.getString("chief_complaint"), rs.getLong("version"),
                rs.getTimestamp("started_at").toInstant(), finalized == null ? null : finalized.toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
