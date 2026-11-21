package com.hms.admission.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

/** Admissions (V37). Under RLS; every query also filters by organization. Never deleted. */
@Repository
public class AdmissionRepository {

    public record AdmissionRow(UUID id, String admissionNo, UUID patientId, UUID admittingDoctorId,
            UUID sourceEncounterId, String admissionDiagnosis, String status, Instant admittedAt,
            Instant plannedDischargeAt, Instant dischargedAt, long version, Instant createdAt, Instant updatedAt) {
    }

    /** {@code ids} restricts the list to those admissions (the ward filter); null means no restriction. */
    public record ListFilter(String status, UUID patientId, Collection<UUID> ids) {
    }

    private static final String COLUMNS = "id, admission_no, patient_id, admitting_doctor_id, source_encounter_id,"
            + " admission_diagnosis, status, admitted_at, planned_discharge_at, discharged_at, version, created_at,"
            + " updated_at";
    private static final String WHERE = """
             FROM hms.admission WHERE organization_id = :org
              AND (CAST(:status AS text) IS NULL OR status = :status)
              AND (CAST(:patient AS uuid) IS NULL OR patient_id = :patient)
            """;

    private final JdbcClient jdbc;

    AdmissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an ADMITTED admission with its number (allocated just before; the insert takes no row lock others
     * wait on, so the sequence lock stays the last one that matters). False when the
     * patient already has an active admission: the partial unique index (I3) decides, so concurrent admits are safe.
     */
    public boolean insert(UUID id, UUID org, String admissionNo, UUID patientId, UUID doctorId, UUID sourceEncounterId, String diagnosis,
            Instant admittedAt, UUID createdBy) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.admission (id, organization_id, admission_no, patient_id, admitting_doctor_id,
                        source_encounter_id, admission_diagnosis, status, admitted_at, created_by, created_at,
                        updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'ADMITTED', ?, ?, ?, ?)
                    """).params(id, org, admissionNo, patientId, doctorId, sourceEncounterId, diagnosis, Timestamp.from(admittedAt),
                    createdBy, Timestamp.from(admittedAt), Timestamp.from(admittedAt)).update();
            return true;
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_admission_patient_active")) {
                return false;
            }
            throw e;
        }
    }

    public Optional<AdmissionRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.admission WHERE organization_id = ? AND id = ?")
                .params(org, id).query(AdmissionRepository::map).optional();
    }

    /** Row lock; taken after any bed lock (lock order: bed, admission, invoice). */
    public Optional<AdmissionRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.admission WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(AdmissionRepository::map).optional();
    }

    public List<AdmissionRow> list(UUID org, ListFilter filter, int limit, long offset) {
        if (filter.ids() != null && filter.ids().isEmpty()) {
            return List.of();
        }
        return bind(jdbc.sql("SELECT " + COLUMNS + WHERE + idClause(filter)
                + " ORDER BY admitted_at DESC, id DESC LIMIT :limit OFFSET :offset"), org, filter)
                .param("limit", limit).param("offset", offset).query(AdmissionRepository::map).list();
    }

    public long count(UUID org, ListFilter filter) {
        if (filter.ids() != null && filter.ids().isEmpty()) {
            return 0;
        }
        return bind(jdbc.sql("SELECT count(*)" + WHERE + idClause(filter)), org, filter).query(Long.class).single();
    }

    /** Compare-and-set on {@code version}. */
    public boolean updateDiagnosis(UUID org, UUID id, long expectedVersion, String diagnosis, Instant now) {
        return jdbc.sql("""
                UPDATE hms.admission SET admission_diagnosis = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(diagnosis, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    /** Compare-and-set on {@code version}. */
    public boolean updateStatus(UUID org, UUID id, long expectedVersion, String status, Instant now) {
        return jdbc.sql("""
                UPDATE hms.admission SET status = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(status, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    /** [E8-7] Planned discharge and status (ADMITTED to DISCHARGE_INITIATED on the first summary save); CAS on version. */
    public boolean updatePlannedDischarge(UUID org, UUID id, long expectedVersion, String status, Instant planned,
            Instant now) {
        return jdbc.sql("""
                UPDATE hms.admission SET status = ?, planned_discharge_at = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(status, Timestamp.from(planned), Timestamp.from(now), org, id, expectedVersion)
                .update() == 1;
    }

    /** [E8-7] DISCHARGE_INITIATED to DISCHARGED at {@code dischargedAt}; CAS on version. */
    public boolean discharge(UUID org, UUID id, long expectedVersion, Instant dischargedAt, Instant now) {
        return jdbc.sql("""
                UPDATE hms.admission SET status = 'DISCHARGED', discharged_at = ?, version = version + 1,
                    updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ? AND status = 'DISCHARGE_INITIATED'
                """).params(Timestamp.from(dischargedAt), Timestamp.from(now), org, id, expectedVersion)
                .update() == 1;
    }

    private static String idClause(ListFilter filter) {
        return filter.ids() == null ? "" : " AND id IN (:ids)";
    }

    private static StatementSpec bind(StatementSpec spec, UUID org, ListFilter filter) {
        spec = spec.param("org", org).param("status", filter.status(), Types.VARCHAR)
                .param("patient", filter.patientId());
        return filter.ids() == null ? spec : spec.param("ids", filter.ids());
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static AdmissionRow map(ResultSet rs, int n) throws SQLException {
        return new AdmissionRow(rs.getObject("id", UUID.class), rs.getString("admission_no"),
                rs.getObject("patient_id", UUID.class), rs.getObject("admitting_doctor_id", UUID.class),
                rs.getObject("source_encounter_id", UUID.class), rs.getString("admission_diagnosis"),
                rs.getString("status"), instant(rs, "admitted_at"), instant(rs, "planned_discharge_at"),
                instant(rs, "discharged_at"), rs.getLong("version"), instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }
}
