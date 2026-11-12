package com.hms.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.audit.api.AuditEntry;
import com.hms.audit.api.AuditOutcome;
import com.hms.audit.api.AuditRecorder;
import com.hms.audit.infrastructure.AuditPartitionMaintenance;
import com.hms.shared.web.CorrelationIdFilter;
import com.hms.support.PostgresIT;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [E1-1] Audit core against real PostgreSQL with the ADR-0015 roles: append-only enforcement, same-transaction
 * writes and the no-PHI metadata rule (ADR-0007, PRD AUD-2/AUD-4, invariant I8).
 */
@SpringBootTest
class AuditRecorderIT extends PostgresIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String IMMUTABLE_ROW = "HM001";
    private static final String CHECK_VIOLATION = "23514";

    @Autowired
    AuditRecorder recorder;

    @Autowired
    AuditPartitionMaintenance partitions;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** actor_id references app_user ([E1-3]), so audited actions need a real (synthetic) user. */
    private static final UUID ACTOR = UUID.fromString("0190d000-0000-7000-8000-00000000000d");

    @BeforeEach
    void ensureActorExists() {
        jdbc.sql("""
                INSERT INTO hms.app_user (id, organization_id, email, full_name, password_hash, created_at, updated_at)
                VALUES (?, ?, 'audit-actor@demo-clinic.example', 'Audit Actor', 'not-a-hash', now(), now())
                ON CONFLICT (id) DO NOTHING""")
                .params(ACTOR, UUID.randomUUID())
                .update();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    // --- Acceptance criterion: UPDATE/DELETE on audit rejected --------------------------------------------------

    @Test
    void appRoleHasNoUpdateDeleteOrTruncatePrivilege() throws SQLException {
        UUID org = UUID.randomUUID();
        inTransaction(() -> recorder.record(viewed(org)));
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> app.createStatement().execute(
                    "UPDATE hms.audit_event SET action = 'TAMPERED' WHERE organization_id = '" + org + "'"),
                    INSUFFICIENT_PRIVILEGE);
            assertSqlState(() -> app.createStatement().execute(
                    "DELETE FROM hms.audit_event WHERE organization_id = '" + org + "'"), INSUFFICIENT_PRIVILEGE);
            assertSqlState(() -> app.createStatement().execute("TRUNCATE hms.audit_event"), INSUFFICIENT_PRIVILEGE);
        }
        assertThat(countFor(org)).isEqualTo(1);
    }

    /** Triggers fire for every role, so even the owner (or an operator) cannot rewrite history. */
    @Test
    void triggersRejectUpdateDeleteAndTruncateEvenForOwner() throws SQLException {
        UUID org = UUID.randomUUID();
        inTransaction(() -> recorder.record(viewed(org)));
        try (Connection owner = connect(OWNER, OWNER_PASSWORD)) {
            assertSqlState(() -> owner.createStatement().execute(
                    "UPDATE hms.audit_event SET action = 'TAMPERED' WHERE organization_id = '" + org + "'"),
                    IMMUTABLE_ROW);
            assertSqlState(() -> owner.createStatement().execute(
                    "DELETE FROM hms.audit_event WHERE organization_id = '" + org + "'"), IMMUTABLE_ROW);
            assertSqlState(() -> owner.createStatement().execute("TRUNCATE hms.audit_event"), IMMUTABLE_ROW);
            assertSqlState(() -> owner.createStatement().execute("TRUNCATE hms." + currentPartition()),
                    IMMUTABLE_ROW);
        }
        assertThat(countFor(org)).isEqualTo(1);
    }

    @Test
    void appRoleCannotBypassParentThroughAPartition() throws SQLException {
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> app.createStatement().executeQuery("SELECT 1 FROM hms." + currentPartition()),
                    INSUFFICIENT_PRIVILEGE);
        }
    }

    // --- Acceptance criterion: same-transaction write -----------------------------------------------------------

    @Test
    void auditCommitsWithTheBusinessChange() {
        UUID org = UUID.randomUUID();
        inTransaction(() -> {
            businessWrite(org);
            recorder.record(viewed(org));
        });
        assertThat(businessRows(org)).isEqualTo(1);
        assertThat(countFor(org)).isEqualTo(1);
    }

    @Test
    void auditRollsBackWithTheBusinessChange() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            businessWrite(org);
            recorder.record(viewed(org));
            throw new IllegalStateException("business rule failed after auditing");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(businessRows(org)).isZero();
        assertThat(countFor(org)).isZero();
    }

    @Test
    void failingAuditWriteRollsBackTheBusinessChange() {
        UUID org = UUID.randomUUID();
        // A correlation id the DB check rejects makes the audit INSERT fail at flush, inside the same transaction.
        MDC.put(CorrelationIdFilter.MDC_KEY, "not allowed!");
        assertThatThrownBy(() -> inTransaction(() -> {
            businessWrite(org);
            recorder.record(viewed(org));
        })).isInstanceOf(RuntimeException.class);
        assertThat(businessRows(org)).isZero();
        assertThat(countFor(org)).isZero();
    }

    @Test
    void recordingOutsideATransactionIsRefused() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> recorder.record(viewed(org))).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(countFor(org)).isZero();
    }

    @Test
    void storesActorEntityOutcomeAndCorrelationId() {
        UUID org = UUID.randomUUID();
        UUID actor = ACTOR;
        UUID patient = UUID.randomUUID();
        MDC.put(CorrelationIdFilter.MDC_KEY, "it-audit-1");
        inTransaction(() -> recorder.record(
                AuditEntry.of(org, actor, "PATIENT_VIEWED", "PATIENT", patient, AuditOutcome.SUCCESS)));

        var row = jdbc.sql("""
                        SELECT actor_id, action, entity_type, entity_id, outcome, correlation_id, occurred_at
                        FROM hms.audit_event WHERE organization_id = :org""")
                .param("org", org)
                .query((rs, n) -> List.of(rs.getObject(1), rs.getString(2), rs.getString(3), rs.getObject(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7) != null))
                .single();
        assertThat(row).containsExactly(actor, "PATIENT_VIEWED", "PATIENT", patient, "SUCCESS", "it-audit-1", true);
    }

    // --- Acceptance criterion: no PHI in metadata ---------------------------------------------------------------

    @Test
    void metadataHoldsFieldNamesOnly() throws SQLException {
        UUID org = UUID.randomUUID();
        inTransaction(() -> recorder.record(new AuditEntry(org, ACTOR, "PATIENT_UPDATED", "PATIENT",
                UUID.randomUUID(), AuditOutcome.SUCCESS, List.of("phone", "address.city"))));

        try (Connection owner = connect(OWNER, OWNER_PASSWORD);
                PreparedStatement ps = owner.prepareStatement(
                        "SELECT changed_fields FROM hms.audit_event WHERE organization_id = ?")) {
            ps.setObject(1, org);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Array fields = rs.getArray(1);
                assertThat((String[]) fields.getArray()).containsExactly("address.city", "phone");
            }
        }
        // The table has no free-text column a value could be smuggled into.
        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                        WHERE table_schema = 'hms' AND table_name = 'audit_event' ORDER BY ordinal_position""")
                .query(String.class).list())
                .containsExactly("id", "organization_id", "occurred_at", "actor_id", "action", "entity_type",
                        "entity_id", "outcome", "correlation_id", "changed_fields");
    }

    @Test
    void recorderRejectsValuesInChangedFieldsBeforeTouchingTheDatabase() {
        UUID org = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> recorder.record(new AuditEntry(org, null, "PATIENT_UPDATED",
                "PATIENT", UUID.randomUUID(), AuditOutcome.SUCCESS, List.of("9000000001")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("9000000001");
        assertThat(countFor(org)).isZero();
    }

    /** Defence in depth: a direct INSERT that bypasses {@link AuditEntry} is still refused by the DB. */
    @Test
    void databaseRejectsValueLikeChangedFields() throws SQLException {
        String insert = """
                INSERT INTO hms.audit_event (id, organization_id, occurred_at, action, entity_type, outcome, changed_fields)
                VALUES (?, ?, now(), 'PATIENT_UPDATED', 'PATIENT', 'SUCCESS', ?)""";
        try (Connection app = connect(APP, APP_PASSWORD)) {
            for (String value : new String[] {"9000000001", "test.user@example.invalid", "Fever since three days"}) {
                assertSqlState(() -> {
                    try (PreparedStatement ps = app.prepareStatement(insert)) {
                        ps.setObject(1, UUID.randomUUID());
                        ps.setObject(2, UUID.randomUUID());
                        ps.setArray(3, app.createArrayOf("text", new String[] {"phone", value}));
                        ps.executeUpdate();
                    }
                }, CHECK_VIOLATION);
            }
            // Same statement with field names only succeeds, so the negative case fails for the right reason.
            try (PreparedStatement ps = app.prepareStatement(insert)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.randomUUID());
                ps.setArray(3, app.createArrayOf("text", new String[] {"phone", "address.city"}));
                assertThat(ps.executeUpdate()).isEqualTo(1);
            }
        }
    }

    // --- Partition job ------------------------------------------------------------------------------------------

    @Test
    void partitionsExistForCurrentAndNextThreeMonthsAndJobIsIdempotent() {
        YearMonth now = YearMonth.now(ZoneOffset.UTC);
        List<String> expected = List.of(partitionName(now), partitionName(now.plusMonths(1)),
                partitionName(now.plusMonths(2)), partitionName(now.plusMonths(3)));
        assertThat(jdbc.sql("""
                        SELECT c.relname FROM pg_inherits i
                        JOIN pg_class c ON c.oid = i.inhrelid
                        WHERE i.inhparent = 'hms.audit_event'::regclass""")
                .query(String.class).list())
                .containsAll(expected);
        assertThat(partitions.ensurePartitions()).isZero();
    }

    @Test
    void partitionFunctionRejectsOutOfRangeHorizon() throws SQLException {
        try (Connection app = connect(APP, APP_PASSWORD)) {
            assertSqlState(() -> app.createStatement().executeQuery("SELECT hms.ensure_audit_event_partitions(13)"),
                    "22023");
        }
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private static AuditEntry viewed(UUID org) {
        return AuditEntry.of(org, ACTOR, "PATIENT_VIEWED", "PATIENT", UUID.randomUUID(),
                AuditOutcome.SUCCESS);
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    /** Stand-in for a module's own write: a row in the only business table V1 has. */
    private void businessWrite(UUID org) {
        jdbc.sql("""
                        INSERT INTO hms.document_sequence (organization_id, document_type, period_key, created_at, updated_at)
                        VALUES (:org, 'UHID', 'ALL', now(), now())""")
                .param("org", org)
                .update();
    }

    private int businessRows(UUID org) {
        return jdbc.sql("SELECT count(*) FROM hms.document_sequence WHERE organization_id = :org")
                .param("org", org).query(Integer.class).single();
    }

    private int countFor(UUID org) {
        return jdbc.sql("SELECT count(*) FROM hms.audit_event WHERE organization_id = :org")
                .param("org", org).query(Integer.class).single();
    }

    private static String currentPartition() {
        return partitionName(YearMonth.now(ZoneOffset.UTC));
    }

    private static String partitionName(YearMonth month) {
        return "audit_event_" + month.format(DateTimeFormatter.ofPattern("yyyy_MM"));
    }

    private static Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), user, password);
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
