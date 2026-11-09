package com.hms.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.support.PostgresIT;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * [E0-6] Flyway baseline V1 against a real PostgreSQL with the ADR-0015 roles.
 * Design: docs/architecture/database-design.md section 9. Starting the context runs Flyway as {@code hms_owner}.
 */
@SpringBootTest
class BaselineMigrationIT extends PostgresIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String IMMUTABLE_ROW = "HM001";

    private static final String ALLOCATE = """
            INSERT INTO hms.document_sequence (organization_id, document_type, period_key, next_value, created_at, updated_at)
            VALUES (?, ?, ?, 2, now(), now())
            ON CONFLICT (organization_id, document_type, period_key)
            DO UPDATE SET next_value = hms.document_sequence.next_value + 1, updated_at = EXCLUDED.updated_at
            RETURNING next_value - 1""";

    private static final String[] DDL = {
        "CREATE TABLE hms.it_ddl_probe (id int)",
        "CREATE TABLE public.it_ddl_probe (id int)",
        "ALTER TABLE hms.document_sequence ADD COLUMN it_probe int",
        "DROP TABLE hms.document_sequence",
        "CREATE TEMP TABLE it_ddl_probe (id int)",
        "CREATE SCHEMA it_probe",
    };

    // --- Acceptance criterion: migration runs on Testcontainers -------------------------------------------------

    @Test
    void baselineIsAppliedByOwnerAndUsableByApp() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD);
                ResultSet rs = owner.createStatement().executeQuery(
                        "SELECT version, success, installed_by FROM hms.flyway_schema_history WHERE version = '1'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("success")).isTrue();
            assertThat(rs.getString("installed_by")).isEqualTo(OWNER);
        }
        try (Connection app = connect(APP, APP_PASSWORD);
                ResultSet rs = app.createStatement().executeQuery("SELECT count(*) FROM hms.document_sequence")) {
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void extensionsAreInstalledInSchemaHms() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD);
                ResultSet rs = owner.createStatement().executeQuery("""
                        SELECT count(*) FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
                        WHERE n.nspname = 'hms' AND e.extname IN ('btree_gist', 'pg_trgm')""")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    // --- Acceptance criterion: app role lacks DDL ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void appRoleCannotRunDdl(int statement) throws SQLException {
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> app.createStatement().execute(DDL[statement]), INSUFFICIENT_PRIVILEGE);
        }
    }

    /** Guards the negative test above: the same statements are valid and succeed for the owner. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void ownerCanRunTheSameDdl(int statement) throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD)) {
            owner.setAutoCommit(false);
            owner.createStatement().execute(DDL[statement]);
            owner.rollback();
        }
    }

    @Test
    void appRoleHasNoElevatedAttributesAndOwnsNothing() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD);
                ResultSet rs = owner.createStatement().executeQuery("""
                        SELECT r.rolsuper, r.rolcreatedb, r.rolcreaterole, r.rolbypassrls,
                               (SELECT count(*) FROM pg_class c WHERE c.relowner = r.oid) AS owned
                        FROM pg_roles r WHERE r.rolname = 'hms_app'""")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("rolsuper")).isFalse();
            assertThat(rs.getBoolean("rolcreatedb")).isFalse();
            assertThat(rs.getBoolean("rolcreaterole")).isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).isFalse();
            assertThat(rs.getLong("owned")).isZero();
        }
    }

    // --- document_sequence --------------------------------------------------------------------------------------

    @Test
    void allocatesSequentialNumbersPerKey() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertThat(allocate(app, org, "INVOICE", "FY2026-27")).isEqualTo(1);
            assertThat(allocate(app, org, "INVOICE", "FY2026-27")).isEqualTo(2);
            assertThat(allocate(app, org, "INVOICE", "FY2026-27")).isEqualTo(3);
            assertThat(allocate(app, org, "INVOICE", "FY2027-28")).isEqualTo(1);
            assertThat(allocate(app, org, "RECEIPT", "FY2026-27")).isEqualTo(1);
            assertThat(allocate(app, UUID.randomUUID(), "INVOICE", "FY2026-27")).isEqualTo(1);
        }
    }

    @Test
    void rolledBackAllocationDoesNotConsumeANumber() throws SQLException {
        UUID org = UUID.randomUUID();
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertThat(allocate(app, org, "UHID", "ALL")).isEqualTo(1);
            app.setAutoCommit(false);
            assertThat(allocate(app, org, "UHID", "ALL")).isEqualTo(2);
            app.rollback();
            assertThat(allocate(app, org, "UHID", "ALL")).isEqualTo(2);
            app.commit();
        }
    }

    @Test
    void concurrentAllocationsForTheSameKeySerialiseWithoutGaps() throws Exception {
        UUID org = UUID.randomUUID();
        try (Connection first = connect(APP, APP_PASSWORD); Connection second = connect(APP, APP_PASSWORD)) {
            assertThat(allocate(first, org, "INVOICE", "FY2026-27")).isEqualTo(1);
            first.setAutoCommit(false);
            second.setAutoCommit(false);

            assertThat(allocate(first, org, "INVOICE", "FY2026-27")).isEqualTo(2);
            CompletableFuture<Long> blocked = CompletableFuture.supplyAsync(() -> {
                try {
                    return allocate(second, org, "INVOICE", "FY2026-27");
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(300);
            assertThat(blocked).as("second issuer waits for the first one's row lock").isNotDone();

            first.commit();
            assertThat(blocked.get(10, TimeUnit.SECONDS)).isEqualTo(3);
            second.commit();
        }
    }

    @Test
    void appRoleCannotDeleteSequenceRows() throws SQLException {
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> app.createStatement().execute("DELETE FROM hms.document_sequence"),
                    INSUFFICIENT_PRIVILEGE);
        }
    }

    @Test
    void sequenceRejectsUnknownTypeAndMalformedPeriod() throws SQLException {
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> allocate(app, UUID.randomUUID(), "PATIENT", "ALL"), "23514");
            assertSqlState(() -> allocate(app, UUID.randomUUID(), "INVOICE", "2026"), "23514");
        }
    }

    // --- hms.reject_modification() -----------------------------------------------------------------------------

    @Test
    void immutabilityTriggerRejectsUpdateDeleteAndTruncateForBothRoles() throws SQLException {
        try (Connection owner = connect(OWNER, OWNER_PASSWORD); Connection app = connect(APP, APP_PASSWORD)) {
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("CREATE TABLE hms.it_append_only (id int PRIMARY KEY)");
                ddl.execute("""
                        CREATE TRIGGER trg_it_append_only_immutable BEFORE UPDATE OR DELETE ON hms.it_append_only
                        FOR EACH ROW EXECUTE FUNCTION hms.reject_modification()""");
                ddl.execute("""
                        CREATE TRIGGER trg_it_append_only_no_truncate BEFORE TRUNCATE ON hms.it_append_only
                        FOR EACH STATEMENT EXECUTE FUNCTION hms.reject_modification()""");
                ddl.execute("GRANT DELETE ON hms.it_append_only TO hms_app");
                ddl.execute("INSERT INTO hms.it_append_only VALUES (1)");
            }
            try {
                for (Connection role : new Connection[] {owner, app}) {
                    assertSqlState(() -> role.createStatement().execute("UPDATE hms.it_append_only SET id = 2"),
                            IMMUTABLE_ROW);
                    assertSqlState(() -> role.createStatement().execute("DELETE FROM hms.it_append_only"),
                            IMMUTABLE_ROW);
                }
                assertSqlState(() -> owner.createStatement().execute("TRUNCATE hms.it_append_only"), IMMUTABLE_ROW);
                try (ResultSet rs = app.createStatement().executeQuery("SELECT id FROM hms.it_append_only")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            } finally {
                owner.createStatement().execute("DROP TABLE hms.it_append_only");
            }
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
    }

    private static long allocate(Connection connection, UUID org, String type, String period) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(ALLOCATE)) {
            ps.setObject(1, org);
            ps.setString(2, type);
            ps.setString(3, period);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private interface SqlAction {
        void run() throws SQLException;
    }

    private static void assertSqlState(SqlAction action, String sqlState) {
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .satisfies(e -> assertThat(((SQLException) e).getSQLState()).isEqualTo(sqlState));
    }
}
