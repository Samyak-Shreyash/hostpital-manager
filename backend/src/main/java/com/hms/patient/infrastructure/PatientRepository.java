package com.hms.patient.infrastructure;

import com.hms.patient.application.PatientView.AddressView;
import com.hms.patient.application.PatientView.ContactView;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Patients and their address and emergency contact (V10). All tables are under RLS; queries also filter by org. */
@Repository
public class PatientRepository {

    public record PatientRow(UUID id, String uhid, String fullName, String sex, LocalDate dateOfBirth,
            boolean dobEstimated, String phone, String abhaLast4, String status, long version, Instant createdAt,
            Instant updatedAt) {
    }

    /** ABHA columns (V14): AES-GCM value + key id, HMAC lookup hash, last 4 digits. */
    public record AbhaColumns(byte[] encrypted, String keyId, byte[] hash, String last4) {
    }

    /** Search filters; null = not given. {@code name} is a trigram query, the rest are exact. */
    public record SearchFilter(String name, String uhid, String phone, byte[] abhaHash, LocalDate dateOfBirth) {
    }

    private final JdbcClient jdbc;

    PatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return false when another patient of the organization already has this ABHA number (nothing inserted) */
    public boolean insertPatient(UUID id, UUID org, String uhid, String fullName, String sex, LocalDate dateOfBirth,
            boolean dobEstimated, String phone, AbhaColumns abha, UUID createdBy, Instant now) {
        AbhaColumns a = abha == null ? new AbhaColumns(null, null, null, null) : abha;
        return jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, dob_estimated,
                    phone, abha_encrypted, abha_key_id, abha_hash, abha_last4, status, created_by, created_at,
                    updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                ON CONFLICT (organization_id, abha_hash) WHERE abha_hash IS NOT NULL DO NOTHING
                """).params(id, org, uhid, fullName, sex, dateOfBirth, dobEstimated, phone, a.encrypted(), a.keyId(),
                a.hash(), a.last4(), createdBy, Timestamp.from(now), Timestamp.from(now)).update() == 1;
    }

    public void insertAddress(UUID id, UUID org, UUID patientId, AddressView a, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.patient_address (id, organization_id, patient_id, line1, line2, city, state, pincode,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, org, patientId, a.line1(), a.line2(), a.city(), a.state(), a.pincode(),
                Timestamp.from(now), Timestamp.from(now)).update();
    }

    public void insertEmergencyContact(UUID id, UUID org, UUID patientId, ContactView c, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.patient_contact (id, organization_id, patient_id, name, relationship, phone,
                    created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, org, patientId, c.name(), c.relationship(), c.phone(), Timestamp.from(now),
                Timestamp.from(now)).update();
    }

    public Optional<PatientRow> findPatient(UUID org, UUID id) {
        return jdbc.sql("""
                SELECT id, uhid, full_name, sex, date_of_birth, dob_estimated, phone, abha_last4, status, version,
                    created_at,
                    updated_at
                FROM hms.patient WHERE organization_id = ? AND id = ?
                """).params(org, id).query(PatientRepository::mapPatient).optional();
    }

    public Optional<AddressView> findCurrentAddress(UUID org, UUID patientId) {
        return jdbc.sql("""
                SELECT line1, line2, city, state, pincode FROM hms.patient_address
                WHERE organization_id = ? AND patient_id = ? AND address_type = 'CURRENT' AND is_current
                """).params(org, patientId).query((rs, n) -> new AddressView(rs.getString("line1"),
                rs.getString("line2"), rs.getString("city"), rs.getString("state"), rs.getString("pincode")))
                .optional();
    }

    public Optional<ContactView> findEmergencyContact(UUID org, UUID patientId) {
        return jdbc.sql("""
                SELECT name, relationship, phone FROM hms.patient_contact
                WHERE organization_id = ? AND patient_id = ? AND contact_type = 'EMERGENCY'
                ORDER BY created_at, id LIMIT 1
                """).params(org, patientId).query((rs, n) -> new ContactView(rs.getString("name"),
                rs.getString("relationship"), rs.getString("phone"))).optional();
    }

    /**
     * Fuzzy name (pg_trgm similarity at the default 0.3 threshold, or a substring match; both are served by
     * ix_patient_full_name_trgm) AND exact UHID / phone / date of birth. Best name match first.
     */
    public List<PatientRow> search(UUID org, SearchFilter f, int limit, long offset) {
        Where w = where(org, f);
        String order = f.name() == null ? "full_name, id"
                : "hms.similarity(full_name, :name) DESC, full_name, id";
        var spec = jdbc.sql("""
                SELECT id, uhid, full_name, sex, date_of_birth, dob_estimated, phone, abha_last4, status, version,
                    created_at,
                    updated_at
                FROM hms.patient WHERE %s ORDER BY %s LIMIT :limit OFFSET :offset
                """.formatted(w.sql(), order)).param("limit", limit).param("offset", offset);
        for (var e : w.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(PatientRepository::mapPatient).list();
    }

    public long countSearch(UUID org, SearchFilter f) {
        Where w = where(org, f);
        var spec = jdbc.sql("SELECT count(*) FROM hms.patient WHERE " + w.sql());
        for (var e : w.params().entrySet()) {
            spec = spec.param(e.getKey(), e.getValue());
        }
        return spec.query(Long.class).single();
    }

    /** A row matching the duplicate rule, with its name similarity. */
    public record CandidateRow(UUID id, String uhid, String fullName, LocalDate dateOfBirth, String phone,
            double score) {
    }

    /**
     * Duplicate candidates (BR-P3): same date of birth and similarity &gt;= {@code dobThreshold}, or same phone and
     * similarity &gt;= {@code phoneThreshold}. Merged records are skipped (they point at a surviving patient). Uses
     * ix_patient_org_dob and ix_patient_org_phone; similarity is computed only on those few rows.
     */
    public List<CandidateRow> findDuplicateCandidates(UUID org, String fullName, LocalDate dateOfBirth, String phone,
            BigDecimal dobThreshold, BigDecimal phoneThreshold, int limit) {
        return jdbc.sql("""
                SELECT id, uhid, full_name, date_of_birth, phone, score FROM (
                    SELECT id, uhid, full_name, date_of_birth, phone,
                        hms.similarity(full_name, :name) AS score
                    FROM hms.patient
                    WHERE organization_id = :org AND status <> 'MERGED'
                      AND (date_of_birth = :dob OR phone = :phone)
                ) c
                WHERE (date_of_birth = :dob AND score >= :dobThreshold)
                   OR (phone = :phone AND score >= :phoneThreshold)
                ORDER BY score DESC, uhid
                LIMIT :limit
                """).param("org", org).param("name", fullName).param("dob", dateOfBirth).param("phone", phone)
                .param("dobThreshold", dobThreshold).param("phoneThreshold", phoneThreshold).param("limit", limit)
                .query((rs, n) -> new CandidateRow(rs.getObject("id", UUID.class), rs.getString("uhid"),
                        rs.getString("full_name"), rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("phone"), rs.getDouble("score")))
                .list();
    }

    /** Append-only record of a confirmed "not a duplicate" override (V15). The reason stays here, not in audit. */
    public void insertDuplicateOverride(UUID id, UUID org, UUID patientId, List<UUID> candidateIds, String reason,
            UUID overriddenBy, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.patient_duplicate_override (id, organization_id, patient_id, candidate_ids, reason,
                    overridden_by, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(id, org, patientId, candidateIds.toArray(UUID[]::new), reason, overriddenBy,
                Timestamp.from(now)).update();
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record Where(String sql, java.util.Map<String, Object> params) {
    }

    private static Where where(UUID org, SearchFilter f) {
        List<String> clauses = new ArrayList<>(List.of("organization_id = :org"));
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put("org", org);
        if (f.name() != null) {
            clauses.add("(full_name OPERATOR(hms.%) :name OR full_name ILIKE :like ESCAPE '\\')");
            params.put("name", f.name());
            params.put("like", "%" + escapeLike(f.name()) + "%");
        }
        if (f.uhid() != null) {
            clauses.add("uhid = :uhid");
            params.put("uhid", f.uhid());
        }
        if (f.phone() != null) {
            clauses.add("phone = :phone");
            params.put("phone", f.phone());
        }
        if (f.abhaHash() != null) {
            clauses.add("abha_hash = :abhaHash");
            params.put("abhaHash", f.abhaHash());
        }
        if (f.dateOfBirth() != null) {
            clauses.add("date_of_birth = :dob");
            params.put("dob", f.dateOfBirth());
        }
        return new Where(String.join(" AND ", clauses), params);
    }

    private static PatientRow mapPatient(ResultSet rs, int n) throws SQLException {
        return new PatientRow(rs.getObject("id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                rs.getString("sex"), rs.getObject("date_of_birth", LocalDate.class), rs.getBoolean("dob_estimated"),
                rs.getString("phone"), rs.getString("abha_last4"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
