package com.hms.shared.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hms.support.PostgresIT;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [E1-2] SP1 spike: PostgreSQL RLS keyed on a transaction-local {@code app.organization_id}, through HikariCP and
 * JPA, as the runtime role {@code hms_app} (ADR-0010, ADR-0015). The pool is pinned to one connection so every
 * transaction provably reuses the same physical connection. The protected table is a test-only
 * migration ({@code db/spike/V1000__rls_spike.sql}); this class only exercises the Java transaction path.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.flyway.locations=classpath:db/migration,classpath:db/spike"})
class RowLevelSecurityFeasibilityIT extends PostgresIT {

    private static final UUID ORG_A = UUID.fromString("0190a000-0000-7000-8000-00000000000a");
    private static final UUID ORG_B = UUID.fromString("0190b000-0000-7000-8000-00000000000b");

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @PersistenceContext
    EntityManager entityManager;

    @Test
    void transactionSeesOnlyItsOwnOrganizationsRows() {
        assertThat(inOrg(ORG_A, this::visibleOrgs)).containsOnly(ORG_A);
        assertThat(inOrg(ORG_B, this::visibleOrgs)).containsOnly(ORG_B);
    }

    @Test
    void writingAnotherOrganizationsRowIsRejected() {
        assertThatThrownBy(() -> inOrg(ORG_A, () -> jdbc.sql("INSERT INTO hms.rls_spike VALUES (?, ?, 'x')")
                        .params(UUID.randomUUID(), ORG_B).update()))
                .rootCause().hasMessageContaining("row-level security policy");
        assertThat(inOrg(ORG_A, () -> jdbc.sql("UPDATE hms.rls_spike SET label = 'x' WHERE organization_id = ?")
                .param(ORG_B).update())).isZero();
    }

    @Test
    void missingContextFailsClosed() {
        assertThat(inTx(this::visibleOrgs)).isEmpty();
    }

    @Test
    void contextDoesNotLeakToTheNextTransactionOnTheSamePooledConnection() {
        int[] pids = new int[2];
        inOrg(ORG_A, () -> pids[0] = backendPid());
        List<UUID> afterwards = inTx(() -> {
            pids[1] = backendPid();
            return visibleOrgs();
        });
        assertThat(pids[1]).as("same physical connection").isEqualTo(pids[0]);
        assertThat(afterwards).isEmpty();
    }

    @Test
    void contextIsClearedOnRollbackToo() {
        assertThatThrownBy(() -> inOrg(ORG_A, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(inTx(this::visibleOrgs)).isEmpty();
    }

    @Test
    void jpaQueriesRunOnTheConnectionCarryingTheContext() {
        @SuppressWarnings("unchecked")
        List<UUID> viaJpa = inOrg(ORG_B, () -> entityManager
                .createNativeQuery("SELECT organization_id FROM hms.rls_spike", UUID.class)
                .getResultList());
        assertThat(viaJpa).containsOnly(ORG_B);
    }

    @Test
    void sessionLevelSetLeaksWhichIsWhyOnlyTransactionLocalIsAllowed() {
        // Documents the rule: is_local=false would survive commit and leak into the next borrower of the connection.
        inTx(() -> jdbc.sql("SELECT set_config('app.organization_id', ?, false)").param(ORG_A.toString())
                .query().singleValue());
        try {
            assertThat(inTx(this::visibleOrgs)).containsOnly(ORG_A);
        } finally {
            inTx(() -> jdbc.sql("RESET app.organization_id").update());
        }
    }

    private List<UUID> visibleOrgs() {
        return jdbc.sql("SELECT organization_id FROM hms.rls_spike").query(UUID.class).list();
    }

    private int backendPid() {
        return jdbc.sql("SELECT pg_backend_pid()").query(Integer.class).single();
    }

    private <T> T inOrg(UUID org, java.util.function.Supplier<T> work) {
        return inTx(() -> {
            jdbc.sql("SELECT set_config('app.organization_id', ?, true)").param(org.toString()).query().singleValue();
            return work.get();
        });
    }

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }
}
