package com.hms.admission.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Discharge summaries and their medications (V39). Under RLS; every query also filters by organization. Summaries are
 * never deleted; the medication list is replaced as a whole. Once the admission is DISCHARGED the V39 guard refuses
 * every change (SQLSTATE HM006).
 */
@Repository
public class DischargeSummaryRepository {

    public record SummaryRow(UUID id, UUID admissionId, String finalDiagnosis, String courseInHospital,
            String procedures, String conditionAtDischarge, String followUp, long version, Instant createdAt,
            Instant updatedAt) {
    }

    public record MedicationRow(String name, String dose, String frequency, String duration) {
    }

    private static final String COLUMNS = "id, admission_id, final_diagnosis, course_in_hospital, procedures,"
            + " condition_at_discharge, follow_up, version, created_at, updated_at";

    private final JdbcClient jdbc;

    DischargeSummaryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<SummaryRow> findByAdmission(UUID org, UUID admissionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.discharge_summary WHERE organization_id = ?"
                + " AND admission_id = ?").params(org, admissionId).query(DischargeSummaryRepository::map).optional();
    }

    public void insert(UUID id, UUID org, UUID admissionId, SummaryRow values, UUID actor, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.discharge_summary (id, organization_id, admission_id, final_diagnosis,
                    course_in_hospital, procedures, condition_at_discharge, follow_up, created_by, created_at,
                    updated_by, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, org, admissionId, values.finalDiagnosis(), values.courseInHospital(),
                values.procedures(), values.conditionAtDischarge(), values.followUp(), actor, Timestamp.from(now),
                actor, Timestamp.from(now)).update();
    }

    /** Compare-and-set on {@code version}. */
    public boolean update(UUID org, UUID id, long expectedVersion, SummaryRow values, UUID actor, Instant now) {
        return jdbc.sql("""
                UPDATE hms.discharge_summary SET final_diagnosis = ?, course_in_hospital = ?, procedures = ?,
                    condition_at_discharge = ?, follow_up = ?, version = version + 1, updated_by = ?, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(values.finalDiagnosis(), values.courseInHospital(), values.procedures(),
                values.conditionAtDischarge(), values.followUp(), actor, Timestamp.from(now), org, id,
                expectedVersion).update() == 1;
    }

    public void replaceMedications(UUID org, UUID summaryId, List<MedicationRow> medications, List<UUID> ids) {
        jdbc.sql("DELETE FROM hms.discharge_medication WHERE organization_id = ? AND summary_id = ?")
                .params(org, summaryId).update();
        for (int i = 0; i < medications.size(); i++) {
            MedicationRow m = medications.get(i);
            jdbc.sql("""
                    INSERT INTO hms.discharge_medication (id, organization_id, summary_id, position, name, dose,
                        frequency, duration) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(ids.get(i), org, summaryId, i, m.name(), m.dose(), m.frequency(), m.duration())
                    .update();
        }
    }

    public List<MedicationRow> medications(UUID org, UUID summaryId) {
        return jdbc.sql("SELECT name, dose, frequency, duration FROM hms.discharge_medication"
                + " WHERE organization_id = ? AND summary_id = ? ORDER BY position")
                .params(org, summaryId).query((rs, n) -> new MedicationRow(rs.getString("name"),
                        rs.getString("dose"), rs.getString("frequency"), rs.getString("duration"))).list();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static SummaryRow map(ResultSet rs, int n) throws SQLException {
        return new SummaryRow(rs.getObject("id", UUID.class), rs.getObject("admission_id", UUID.class),
                rs.getString("final_diagnosis"), rs.getString("course_in_hospital"), rs.getString("procedures"),
                rs.getString("condition_at_discharge"), rs.getString("follow_up"), rs.getLong("version"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }
}
