package com.hms.patient.infrastructure;

import com.hms.patient.application.PatientView.AddressView;
import com.hms.patient.application.PatientView.ContactView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Patients and their address and emergency contact (V10). All tables are under RLS; queries also filter by org. */
@Repository
public class PatientRepository {

    public record PatientRow(UUID id, String uhid, String fullName, String sex, LocalDate dateOfBirth,
            boolean dobEstimated, String phone, String status, long version, Instant createdAt, Instant updatedAt) {
    }

    private final JdbcClient jdbc;

    PatientRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertPatient(UUID id, UUID org, String uhid, String fullName, String sex, LocalDate dateOfBirth,
            boolean dobEstimated, String phone, UUID createdBy, Instant now) {
        jdbc.sql("""
                INSERT INTO hms.patient (id, organization_id, uhid, full_name, sex, date_of_birth, dob_estimated,
                    phone, status, created_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?)
                """).params(id, org, uhid, fullName, sex, dateOfBirth, dobEstimated, phone, createdBy,
                Timestamp.from(now), Timestamp.from(now)).update();
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
                SELECT id, uhid, full_name, sex, date_of_birth, dob_estimated, phone, status, version, created_at,
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

    private static PatientRow mapPatient(ResultSet rs, int n) throws SQLException {
        return new PatientRow(rs.getObject("id", UUID.class), rs.getString("uhid"), rs.getString("full_name"),
                rs.getString("sex"), rs.getObject("date_of_birth", LocalDate.class), rs.getBoolean("dob_estimated"),
                rs.getString("phone"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }
}
