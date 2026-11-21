package com.hms.bedmanagement.infrastructure;

import com.hms.bedmanagement.api.BedAllocation.AssignmentView;
import com.hms.bedmanagement.api.BedAllocation.BedRef;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Bed assignments (V37). Under RLS; every query also filters by organization. hms_app may only set
 * {@code released_at}; an assignment is never deleted.
 */
@Repository
public class BedAssignmentRepository {

    /** A bed locked for assignment with what decides whether it may be assigned. */
    public record LockedBed(BedRef ref, String status, String wardStatus) {
    }

    /** An open assignment and the admission holding it. */
    public record OpenAssignment(UUID admissionId, AssignmentView view) {
    }

    private static final String VIEW = """
            SELECT a.id, a.bed_id, b.bed_no, w.name AS ward_name, b.daily_rate_service_id, a.assigned_at,
                   a.released_at, a.reason, a.admission_id,
                   (w.care_type = 'CRITICAL_CARE') AS critical_care
            FROM hms.bed_assignment a
            JOIN hms.bed b ON b.organization_id = a.organization_id AND b.id = a.bed_id
            JOIN hms.ward w ON w.organization_id = b.organization_id AND w.id = b.ward_id
            """;

    private final JdbcClient jdbc;

    BedAssignmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks only the bed row ({@code FOR UPDATE OF b}): the same row lock bed administration takes. */
    public Optional<LockedBed> lockBed(UUID org, UUID bedId) {
        return jdbc.sql("""
                SELECT b.id, b.bed_no, b.ward_id, w.name AS ward_name, b.daily_rate_service_id, b.status,
                       w.status AS ward_status
                FROM hms.bed b JOIN hms.ward w ON w.organization_id = b.organization_id AND w.id = b.ward_id
                WHERE b.organization_id = ? AND b.id = ?
                FOR UPDATE OF b
                """).params(org, bedId).query((rs, n) -> new LockedBed(new BedRef(rs.getObject("id", UUID.class),
                        rs.getString("bed_no"), rs.getObject("ward_id", UUID.class), rs.getString("ward_name"),
                        rs.getObject("daily_rate_service_id", UUID.class)), rs.getString("status"),
                        rs.getString("ward_status"))).optional();
    }

    /** Locks the beds in ascending id order and returns how many exist in the organization. */
    public int lockBeds(UUID org, Collection<UUID> bedIds) {
        if (bedIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("SELECT id FROM hms.bed WHERE organization_id = :org AND id IN (:ids) ORDER BY id FOR UPDATE")
                .param("org", org).param("ids", bedIds).query(UUID.class).list().size();
    }

    public boolean hasOpenAssignment(UUID org, UUID bedId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM hms.bed_assignment
                               WHERE organization_id = ? AND bed_id = ? AND released_at IS NULL)
                """).params(org, bedId).query(Boolean.class).single();
    }

    /** False when the bed already has an open assignment (I1); the partial unique index decides. */
    public boolean insert(UUID id, UUID org, UUID bedId, UUID admissionId, Instant at, String reason) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.bed_assignment (id, organization_id, bed_id, admission_id, assigned_at,
                        reason)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, org, bedId, admissionId, Timestamp.from(at), reason).update();
            return true;
        } catch (DuplicateKeyException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("uq_bed_assignment_bed_open")) {
                return false;
            }
            throw e;
        }
    }

    /** Sets {@code released_at} of the admission's open assignment; empty when it has none. */
    public Optional<UUID> release(UUID org, UUID admissionId, Instant at) {
        return jdbc.sql("""
                UPDATE hms.bed_assignment SET released_at = ?
                WHERE organization_id = ? AND admission_id = ? AND released_at IS NULL
                RETURNING id
                """).params(Timestamp.from(at), org, admissionId).query(UUID.class).optional();
    }

    public Optional<AssignmentView> find(UUID org, UUID id) {
        return jdbc.sql(VIEW + " WHERE a.organization_id = ? AND a.id = ?").params(org, id)
                .query(BedAssignmentRepository::view).optional();
    }

    public List<AssignmentView> history(UUID org, UUID admissionId) {
        return jdbc.sql(VIEW + " WHERE a.organization_id = ? AND a.admission_id = ? ORDER BY a.assigned_at, a.id")
                .params(org, admissionId).query(BedAssignmentRepository::view).list();
    }

    public List<OpenAssignment> openForAdmissions(UUID org, Collection<UUID> admissionIds) {
        if (admissionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(VIEW + " WHERE a.organization_id = :org AND a.admission_id IN (:ids)"
                        + " AND a.released_at IS NULL").param("org", org).param("ids", admissionIds)
                .query((rs, n) -> new OpenAssignment(rs.getObject("admission_id", UUID.class), view(rs, n))).list();
    }

    /** Open assignments of the given beds as (bed id, admission id) pairs. */
    public List<UUID[]> openForBeds(UUID org, Collection<UUID> bedIds) {
        if (bedIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT bed_id, admission_id FROM hms.bed_assignment
                WHERE organization_id = :org AND bed_id IN (:ids) AND released_at IS NULL
                """).param("org", org).param("ids", bedIds).query((rs, n) -> new UUID[] {
                        rs.getObject("bed_id", UUID.class), rs.getObject("admission_id", UUID.class)}).list();
    }

    public List<UUID> admissionsInWard(UUID org, UUID wardId) {
        return jdbc.sql("""
                SELECT a.admission_id FROM hms.bed_assignment a
                JOIN hms.bed b ON b.organization_id = a.organization_id AND b.id = a.bed_id
                WHERE a.organization_id = ? AND b.ward_id = ? AND a.released_at IS NULL
                """).params(org, wardId).query(UUID.class).list();
    }

    private static AssignmentView view(ResultSet rs, int n) throws SQLException {
        Timestamp released = rs.getTimestamp("released_at");
        return new AssignmentView(rs.getObject("id", UUID.class), rs.getObject("bed_id", UUID.class),
                rs.getString("bed_no"), rs.getString("ward_name"), rs.getObject("daily_rate_service_id", UUID.class),
                rs.getTimestamp("assigned_at").toInstant(), released == null ? null : released.toInstant(),
                rs.getString("reason"), rs.getBoolean("critical_care"));
    }
}
