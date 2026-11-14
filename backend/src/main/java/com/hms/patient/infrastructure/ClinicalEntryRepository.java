package com.hms.patient.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Allergies and medical history (V17): append-only rows, one one-way status change. Under RLS; also filtered by org. */
@Repository
public class ClinicalEntryRepository {

    public static final String ALLERGY = "ALLERGY";
    public static final String HISTORY = "HISTORY";
    public static final String ACTIVE = "ACTIVE";

    /** {@code subject} and {@code detail} are clinical free text: never printed. */
    public record EntryRow(UUID id, String kind, String subject, String detail, String severity, String status,
            Instant createdAt, Instant enteredInErrorAt) {
        @Override
        public String toString() {
            return "EntryRow[id=" + id + ", kind=" + kind + ", status=" + status + "]";
        }
    }

    private static final String COLUMNS =
            "id, kind, subject, detail, severity, status, created_at, entered_in_error_at";

    private final JdbcClient jdbc;

    ClinicalEntryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID org, UUID patientId, String kind, String subject, String detail,
            String severity, UUID recordedBy, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.patient_clinical_entry (id, organization_id, patient_id, kind, subject, detail,
                    severity, status, recorded_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """).params(id, org, patientId, kind, subject, detail, severity, recordedBy, Timestamp.from(now))
                .update();
    }

    public List<EntryRow> list(UUID org, UUID patientId, String kind) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.patient_clinical_entry WHERE organization_id = ? "
                + "AND patient_id = ? AND kind = ? ORDER BY created_at, id").params(org, patientId, kind)
                .query(ClinicalEntryRepository::map).list();
    }

    public Optional<EntryRow> find(UUID org, UUID patientId, String kind, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.patient_clinical_entry WHERE organization_id = ? "
                + "AND patient_id = ? AND kind = ? AND id = ?").params(org, patientId, kind, id)
                .query(ClinicalEntryRepository::map).optional();
    }

    /** The entry locked FOR UPDATE until commit, so two concurrent "entered in error" calls run one after the other. */
    public Optional<EntryRow> lock(UUID org, UUID patientId, String kind, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.patient_clinical_entry WHERE organization_id = ? "
                + "AND patient_id = ? AND kind = ? AND id = ? FOR UPDATE").params(org, patientId, kind, id)
                .query(ClinicalEntryRepository::map).optional();
    }

    public void markEnteredInError(UUID org, UUID id, UUID by, Instant now) {
        jdbc.sql("""
                UPDATE hms.patient_clinical_entry
                SET status = 'ENTERED_IN_ERROR', entered_in_error_by = ?, entered_in_error_at = ?
                WHERE organization_id = ? AND id = ?
                """).params(by, Timestamp.from(now), org, id).update();
    }

    private static EntryRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp marked = rs.getTimestamp("entered_in_error_at");
        return new EntryRow(rs.getObject("id", UUID.class), rs.getString("kind"), rs.getString("subject"),
                rs.getString("detail"), rs.getString("severity"), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(), marked == null ? null : marked.toInstant());
    }
}
