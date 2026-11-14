package com.hms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.hms.support.PostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V21 (owner decisions 2026-11-14) backfills organizations that existed before it: a fresh database is migrated
 * to V20, an organization with the pre-V21 built-in roles and a custom role is inserted, then V21 runs.
 */
class RoleTemplateBackfillMigrationIT extends PostgresIT {

    private static final String DB = "hms_v21_backfill";
    private static final UUID ORG = UUID.fromString("00000000-0000-7000-8000-000000002101");

    private static String url;

    @BeforeAll
    static void migrateToV20ThenSeedThenV21() throws SQLException {
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword()); Statement st = admin.createStatement()) {
            st.execute("DROP DATABASE IF EXISTS " + DB);
            st.execute("CREATE DATABASE " + DB + " OWNER " + OWNER);
        }
        url = postgres.getJdbcUrl().replaceFirst("/test([?]|$)", "/" + DB + "$1");
        flyway("20").migrate();

        try (Connection owner = DriverManager.getConnection(url, OWNER, OWNER_PASSWORD)) {
            owner.setAutoCommit(false);
            try (Statement st = owner.createStatement()) {
                st.execute("SELECT set_config('app.organization_id', '" + ORG + "', true)");
                st.execute("INSERT INTO hms.organization (id, name, status, created_at, updated_at) "
                        + "VALUES ('" + ORG + "', 'Synthetic Clinic', 'ACTIVE', now(), now())");
            }
            role(owner, "FACILITY_ADMIN", "practitioner:read", "audit:read");
            role(owner, "DOCTOR", "patient:read");
            role(owner, "NURSE", "patient:read", "observation:write");
            role(owner, "RECEPTIONIST", "patient:read", "patient:create", "patient:update",
                    "patient:update-clinical", "patient:update-identity", "patient:override-duplicate");
            role(owner, "CUSTOM_DESK", "patient:read", "patient:update-clinical", "patient:update-identity");
            owner.commit();
        }
        flyway(null).migrate();
    }

    private static Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(url, OWNER, OWNER_PASSWORD).schemas("hms").defaultSchema("hms")
                .createSchemas(true).locations("classpath:db/migration");
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    private static void role(Connection c, String code, String... permissions) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO hms.role (id, organization_id, code, name, "
                + "created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())")) {
            ps.setObject(1, id);
            ps.setObject(2, ORG);
            ps.setString(3, code);
            ps.setString(4, "Test " + code);
            ps.executeUpdate();
        }
        for (String permission : permissions) {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO hms.role_permission "
                    + "(organization_id, role_id, permission_code) VALUES (?, ?, ?)")) {
                ps.setObject(1, ORG);
                ps.setObject(2, id);
                ps.setString(3, permission);
                ps.executeUpdate();
            }
        }
    }

    private static Set<String> permissions(String roleCode) throws SQLException {
        try (Connection owner = DriverManager.getConnection(url, OWNER, OWNER_PASSWORD)) {
            owner.setAutoCommit(false);
            try (Statement st = owner.createStatement()) {
                st.execute("SELECT set_config('app.organization_id', '" + ORG + "', true)");
            }
            try (PreparedStatement ps = owner.prepareStatement("SELECT rp.permission_code FROM hms.role_permission rp "
                    + "JOIN hms.role r ON r.id = rp.role_id WHERE r.organization_id = ? AND r.code = ?")) {
                ps.setObject(1, ORG);
                ps.setString(2, roleCode);
                Set<String> codes = new TreeSet<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        codes.add(rs.getString(1));
                    }
                }
                owner.rollback();
                return codes;
            }
        }
    }

    @Test
    void facilityAdministratorGainsStaffPermissions() throws SQLException {
        assertThat(permissions("FACILITY_ADMIN")).containsExactlyInAnyOrder(
                "practitioner:read", "audit:read", "staff:read", "staff:create", "staff:update");
    }

    @Test
    void doctorAndNurseGainClinicalEntryWrites() throws SQLException {
        assertThat(permissions("DOCTOR")).containsExactlyInAnyOrder("patient:read", "patient:update-clinical");
        assertThat(permissions("NURSE")).containsExactlyInAnyOrder(
                "patient:read", "observation:write", "patient:update-clinical");
    }

    @Test
    void receptionistLosesIdentityAndClinicalEdits() throws SQLException {
        assertThat(permissions("RECEPTIONIST")).containsExactlyInAnyOrder(
                "patient:read", "patient:create", "patient:update", "patient:override-duplicate");
    }

    @Test
    void customRolesAreUntouched() throws SQLException {
        assertThat(permissions("CUSTOM_DESK")).containsExactlyInAnyOrder(
                "patient:read", "patient:update-clinical", "patient:update-identity");
    }
}
