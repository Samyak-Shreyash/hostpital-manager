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

/** Beds (V36). Under RLS; every query also filters by organization. */
@Repository
public class BedRepository {

    public record BedRow(UUID id, UUID wardId, String bedNo, String bedClass, UUID dailyRateServiceId, String status,
            long version, Instant createdAt, Instant updatedAt) {
    }

    private static final String COLUMNS =
            "id, ward_id, bed_no, bed_class, daily_rate_service_id, status, version, created_at, updated_at";
    private static final String FK_VIOLATION = "23503";

    private final JdbcClient jdbc;

    BedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * False when the bed number exists in the ward. An unknown or other-organization rate service violates the
     * same-organization foreign key and surfaces as 422.
     */
    public boolean insert(UUID id, UUID org, UUID wardId, String bedNo, String bedClass, UUID rateService,
            Instant now) {
        try {
            return jdbc.sql("""
                    INSERT INTO hms.bed (id, organization_id, ward_id, bed_no, bed_class, daily_rate_service_id,
                        status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                    ON CONFLICT (organization_id, ward_id, bed_no) DO NOTHING
                    """).params(id, org, wardId, bedNo, bedClass, rateService, Timestamp.from(now),
                    Timestamp.from(now)).update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    public Optional<BedRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.bed WHERE organization_id = ? AND id = ?")
                .params(org, id).query(BedRepository::map).optional();
    }

    /** Row lock: bed rows are the first lock of the M4 lock order (bed, admission, invoice). */
    public Optional<BedRow> findForUpdate(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.bed WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(BedRepository::map).optional();
    }

    public List<BedRow> listByWard(UUID org, UUID wardId, String status, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM hms.bed WHERE organization_id = :org AND ward_id = :ward
                  AND (CAST(:status AS text) IS NULL OR status = :status)
                ORDER BY bed_no LIMIT :limit OFFSET :offset
                """).param("org", org).param("ward", wardId).param("status", status, Types.VARCHAR)
                .param("limit", limit).param("offset", offset).query(BedRepository::map).list();
    }

    public long countByWard(UUID org, UUID wardId, String status) {
        return jdbc.sql("""
                SELECT count(*) FROM hms.bed WHERE organization_id = :org AND ward_id = :ward
                 AND (CAST(:status AS text) IS NULL OR status = :status)
                """).param("org", org).param("ward", wardId).param("status", status, Types.VARCHAR)
                .query(Long.class).single();
    }

    /** Every bed of the given wards (no paging: the availability board shows them all). */
    public List<BedRow> listForWards(UUID org, List<UUID> wardIds) {
        if (wardIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.bed WHERE organization_id = :org AND ward_id IN (:wards) "
                + "ORDER BY bed_no").param("org", org).param("wards", wardIds).query(BedRepository::map).list();
    }

    /** Compare-and-set on {@code version}. */
    public boolean update(UUID org, UUID id, long expectedVersion, String bedClass, UUID rateService, String status,
            Instant now) {
        try {
            return jdbc.sql("""
                    UPDATE hms.bed SET bed_class = ?, daily_rate_service_id = ?, status = ?, version = version + 1,
                        updated_at = ?
                    WHERE organization_id = ? AND id = ? AND version = ?
                    """).params(bedClass, rateService, status, Timestamp.from(now), org, id, expectedVersion)
                    .update() == 1;
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && FK_VIOLATION.equals(sql.getSQLState())) {
                return new BusinessRuleException("VALIDATION_ERROR", "dailyRateServiceId does not exist.");
            }
        }
        return e;
    }

    private static BedRow map(ResultSet rs, int n) throws SQLException {
        return new BedRow(rs.getObject("id", UUID.class), rs.getObject("ward_id", UUID.class),
                rs.getString("bed_no"), rs.getString("bed_class"), rs.getObject("daily_rate_service_id", UUID.class),
                rs.getString("status"), rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
