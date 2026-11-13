package com.hms.practitioner.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Practitioners and their specialty/department mappings (V18). All tables are under RLS; queries also filter by org.
 */
@Repository
public class PractitionerRepository {

    public record PractitionerRow(UUID id, UUID userId, String fullName, String registrationNo,
            String registrationCouncil, List<String> qualifications, int defaultSlotMinutes, String status,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record SpecialtyLink(UUID specialtyId, boolean primary) {
    }

    /** Null filters are ignored; {@code likePattern} is already lower-cased, escaped and wrapped in %. */
    public record ListFilter(UUID specialtyId, UUID departmentId, String likePattern, String status) {
    }

    private static final String COLUMNS = """
            p.id, p.user_id, p.full_name, p.registration_no, p.registration_council, p.qualifications,
            p.default_slot_minutes, p.status, p.version, p.created_at, p.updated_at""";

    private static final String FILTERED = """
             FROM hms.practitioner p
            WHERE p.organization_id = :org
              AND (CAST(:status AS text) IS NULL OR p.status = CAST(:status AS text))
              AND (CAST(:like AS text) IS NULL OR lower(p.full_name) LIKE CAST(:like AS text) ESCAPE '\\'
                   OR lower(p.registration_no) LIKE CAST(:like AS text) ESCAPE '\\')
              AND (CAST(:specialty AS uuid) IS NULL OR EXISTS (SELECT 1 FROM hms.practitioner_specialty s
                    WHERE s.organization_id = p.organization_id AND s.practitioner_id = p.id
                      AND s.specialty_id = CAST(:specialty AS uuid)))
              AND (CAST(:department AS uuid) IS NULL OR EXISTS (SELECT 1 FROM hms.practitioner_department d
                    WHERE d.organization_id = p.organization_id AND d.practitioner_id = p.id
                      AND d.department_id = CAST(:department AS uuid)))
            """;

    private final JdbcClient jdbc;

    PractitionerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts unless the user is already linked or the registration number is taken in the organization (the unique
     * indexes decide, so concurrent registrations lose cleanly). @return false when nothing was inserted
     */
    public boolean insertIfAbsent(UUID id, UUID org, UUID userId, String fullName, String registrationNo,
            String registrationCouncil, List<String> qualifications, int defaultSlotMinutes, UUID createdBy,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO hms.practitioner (id, organization_id, user_id, full_name, registration_no,
                    registration_council, qualifications, default_slot_minutes, status, created_by, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT DO NOTHING
                """).params(id, org, userId, fullName, registrationNo, registrationCouncil,
                qualifications.toArray(new String[0]), defaultSlotMinutes, createdBy, Timestamp.from(now),
                Timestamp.from(now)).update() == 1;
    }

    /**
     * Whether a practitioner is linked to this user. Under RLS only the caller's organization is visible, which is
     * enough: the user was already verified to belong to the caller's organization.
     */
    public boolean userLinked(UUID org, UUID userId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM hms.practitioner WHERE organization_id = ? AND user_id = ?)")
                .params(org, userId).query(Boolean.class).single();
    }

    public boolean registrationTaken(UUID org, String registrationNo, UUID excludeId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM hms.practitioner
                               WHERE organization_id = ? AND registration_no = ? AND id <> ?)
                """).params(org, registrationNo, excludeId).query(Boolean.class).single();
    }

    public Optional<PractitionerRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.practitioner p WHERE p.organization_id = ? AND p.id = ?")
                .params(org, id).query(PractitionerRepository::map).optional();
    }

    /** The practitioner row locked FOR UPDATE until commit; empty for an unknown or other-org id. */
    public Optional<PractitionerRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM hms.practitioner p WHERE p.organization_id = ? AND p.id = ? FOR UPDATE")
                .params(org, id).query(PractitionerRepository::map).optional();
    }

    public List<PractitionerRow> list(UUID org, ListFilter f, int limit, long offset) {
        return jdbc.sql("SELECT " + COLUMNS + FILTERED
                        + " ORDER BY lower(p.full_name), p.id LIMIT :limit OFFSET :offset")
                .param("org", org).param("status", f.status(), Types.VARCHAR)
                .param("like", f.likePattern(), Types.VARCHAR)
                .param("specialty", f.specialtyId(), Types.OTHER).param("department", f.departmentId(), Types.OTHER)
                .param("limit", limit).param("offset", offset).query(PractitionerRepository::map).list();
    }

    public long count(UUID org, ListFilter f) {
        return jdbc.sql("SELECT count(*)" + FILTERED)
                .param("org", org).param("status", f.status(), Types.VARCHAR)
                .param("like", f.likePattern(), Types.VARCHAR)
                .param("specialty", f.specialtyId(), Types.OTHER).param("department", f.departmentId(), Types.OTHER)
                .query(Long.class).single();
    }

    /** Profile fields and version bump, guarded by the expected version. @return false on a version mismatch */
    public boolean update(UUID org, UUID id, long expectedVersion, String fullName, String registrationNo,
            String registrationCouncil, List<String> qualifications, int defaultSlotMinutes, String status,
            Instant now) {
        return jdbc.sql("""
                UPDATE hms.practitioner
                SET full_name = ?, registration_no = ?, registration_council = ?, qualifications = ?,
                    default_slot_minutes = ?, status = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(fullName, registrationNo, registrationCouncil, qualifications.toArray(new String[0]),
                defaultSlotMinutes, status, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    /** Version bump for a change to the mappings only. @return false on a version mismatch */
    public boolean touch(UUID org, UUID id, long expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE hms.practitioner SET version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    public List<SpecialtyLink> specialties(UUID org, UUID practitionerId) {
        return jdbc.sql("""
                SELECT specialty_id, is_primary FROM hms.practitioner_specialty
                WHERE organization_id = ? AND practitioner_id = ? ORDER BY is_primary DESC, specialty_id
                """).params(org, practitionerId)
                .query((rs, n) -> new SpecialtyLink(rs.getObject("specialty_id", UUID.class),
                        rs.getBoolean("is_primary"))).list();
    }

    public List<UUID> departments(UUID org, UUID practitionerId) {
        return jdbc.sql("""
                SELECT department_id FROM hms.practitioner_department
                WHERE organization_id = ? AND practitioner_id = ? ORDER BY department_id
                """).params(org, practitionerId).query(UUID.class).list();
    }

    /** Replaces the specialty set (delete + insert; the deferred primary trigger checks the result at commit). */
    public void replaceSpecialties(UUID org, UUID practitionerId, List<SpecialtyLink> links, Instant now) {
        jdbc.sql("DELETE FROM hms.practitioner_specialty WHERE organization_id = ? AND practitioner_id = ?")
                .params(org, practitionerId).update();
        for (SpecialtyLink link : links) {
            jdbc.sql("""
                    INSERT INTO hms.practitioner_specialty (organization_id, practitioner_id, specialty_id,
                        is_primary, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(org, practitionerId, link.specialtyId(), link.primary(), Timestamp.from(now))
                    .update();
        }
    }

    public void replaceDepartments(UUID org, UUID practitionerId, List<UUID> departmentIds, Instant now) {
        jdbc.sql("DELETE FROM hms.practitioner_department WHERE organization_id = ? AND practitioner_id = ?")
                .params(org, practitionerId).update();
        for (UUID departmentId : departmentIds) {
            jdbc.sql("""
                    INSERT INTO hms.practitioner_department (organization_id, practitioner_id, department_id,
                        created_at)
                    VALUES (?, ?, ?, ?)
                    """).params(org, practitionerId, departmentId, Timestamp.from(now)).update();
        }
    }

    private static PractitionerRow map(ResultSet rs, int n) throws SQLException {
        Array array = rs.getArray("qualifications");
        List<String> qualifications = array == null ? List.of() : Arrays.asList((String[]) array.getArray());
        return new PractitionerRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("full_name"), rs.getString("registration_no"), rs.getString("registration_council"),
                List.copyOf(qualifications), rs.getInt("default_slot_minutes"), rs.getString("status"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
