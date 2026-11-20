package com.hms.clinical.infrastructure;

import com.hms.shared.id.UuidV7Generator;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Prescriptions and their items (V28). Tables are under RLS; every query also filters by org. */
@Repository
public class PrescriptionRepository {

    private static final String COLUMNS =
            "id, encounter_id, patient_id, status, version_no, supersedes_id, issued_at, issued_by, version";

    public record PrescriptionRow(UUID id, UUID encounterId, UUID patientId, String status, int versionNo,
            UUID supersedesId, Instant issuedAt, UUID issuedBy, long version) {
    }

    public record NewPrescription(UUID id, UUID org, UUID encounterId, UUID patientId, int versionNo,
            UUID supersedesId, UUID createdBy, Instant now) {
    }

    /** Clinical free text: never printed. */
    public record ItemRow(String drugName, String strength, String form, String route, String dose,
            String frequency, int durationDays, String instructions) {
    }

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    PrescriptionRepository(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /** @return false when the encounter already has a DRAFT or ISSUED prescription (the unique indexes decide) */
    public boolean insert(NewPrescription p) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.prescription (id, organization_id, encounter_id, patient_id, version_no,
                        supersedes_id, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(p.id(), p.org(), p.encounterId(), p.patientId(), p.versionNo(), p.supersedesId(),
                    p.createdBy(), Timestamp.from(p.now()), Timestamp.from(p.now())).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public boolean liveExists(UUID org, UUID encounterId) {
        return jdbc.sql("SELECT count(*) FROM hms.prescription WHERE organization_id = ? AND encounter_id = ?"
                + " AND status IN ('DRAFT', 'ISSUED')").params(org, encounterId).query(Long.class).single() > 0;
    }

    public Optional<PrescriptionRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.prescription WHERE organization_id = ? AND id = ?")
                .params(org, id).query(PrescriptionRepository::map).optional();
    }

    /** The row locked until the transaction ends: state and version are decided on the locked row. */
    public Optional<PrescriptionRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.prescription WHERE organization_id = ? AND id = ?"
                + " FOR UPDATE").params(org, id).query(PrescriptionRepository::map).optional();
    }

    /** DRAFT to ISSUED on the locked row, bumping the version. */
    public void markIssued(UUID org, UUID id, UUID by, Instant now) {
        jdbc.sql("UPDATE hms.prescription SET status = 'ISSUED', issued_at = ?, issued_by = ?,"
                        + " version = version + 1, updated_at = ? WHERE organization_id = ? AND id = ?")
                .params(Timestamp.from(now), by, Timestamp.from(now), org, id).update();
    }

    /** ISSUED to SUPERSEDED (the only change the V28 trigger allows on an issued row). */
    public void markSuperseded(UUID org, UUID id, Instant now) {
        jdbc.sql("UPDATE hms.prescription SET status = 'SUPERSEDED', version = version + 1, updated_at = ?"
                        + " WHERE organization_id = ? AND id = ?")
                .params(Timestamp.from(now), org, id).update();
    }

    /** Bumps the version of the locked DRAFT row. */
    public void touch(UUID org, UUID id, Instant now) {
        jdbc.sql("UPDATE hms.prescription SET version = version + 1, updated_at = ? WHERE organization_id = ?"
                + " AND id = ?").params(Timestamp.from(now), org, id).update();
    }

    /** Replaces the whole item list of a DRAFT (the V28 trigger refuses this on any other status). */
    public void replaceItems(UUID org, UUID prescriptionId, List<ItemRow> items, Instant now) {
        jdbc.sql("DELETE FROM hms.prescription_item WHERE organization_id = ? AND prescription_id = ?")
                .params(org, prescriptionId).update();
        int position = 0;
        for (ItemRow i : items) {
            jdbc.sql("""
                    INSERT INTO hms.prescription_item (id, organization_id, prescription_id, position, drug_name,
                        strength, form, route, dose, frequency, duration_days, instructions, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(ids.next(), org, prescriptionId, position++, i.drugName(), i.strength(),
                    i.form(), i.route(), i.dose(), i.frequency(), i.durationDays(), i.instructions(),
                    Timestamp.from(now)).update();
        }
    }

    public List<ItemRow> items(UUID org, UUID prescriptionId) {
        return jdbc.sql("""
                SELECT drug_name, strength, form, route, dose, frequency, duration_days, instructions
                FROM hms.prescription_item WHERE organization_id = ? AND prescription_id = ? ORDER BY position
                """).params(org, prescriptionId).query((rs, n) -> new ItemRow(rs.getString("drug_name"),
                rs.getString("strength"), rs.getString("form"), rs.getString("route"), rs.getString("dose"),
                rs.getString("frequency"), rs.getInt("duration_days"), rs.getString("instructions"))).list();
    }

    private static PrescriptionRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        Timestamp issued = rs.getTimestamp("issued_at");
        return new PrescriptionRow(rs.getObject("id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("patient_id", UUID.class), rs.getString("status"), rs.getInt("version_no"),
                rs.getObject("supersedes_id", UUID.class), issued == null ? null : issued.toInstant(),
                rs.getObject("issued_by", UUID.class), rs.getLong("version"));
    }
}
