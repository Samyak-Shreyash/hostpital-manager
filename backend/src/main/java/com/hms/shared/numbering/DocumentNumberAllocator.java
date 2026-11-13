package com.hms.shared.numbering;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only code that touches {@code hms.document_sequence} (database-design.md section 6). One upsert allocates
 * the next number; the row lock it takes is held until the caller's transaction ends, so concurrent issuers of
 * the same organization, type and period serialise and a rollback gives the number back (gapless).
 * Call it last in the business transaction to keep the serialised section short.
 */
@Component
public class DocumentNumberAllocator {

    private final JdbcClient jdbc;
    private final Clock clock;

    DocumentNumberAllocator(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Allocates the next number of {@code documentType} for the period ({@code "ALL"} for numbers that never reset).
     * Must run inside the caller's transaction, whose organization context (RLS) must be {@code organizationId}.
     */
    public long next(UUID organizationId, String documentType, String periodKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a document number must be allocated inside the business transaction");
        }
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.sql("""
                INSERT INTO hms.document_sequence
                    (organization_id, document_type, period_key, next_value, created_at, updated_at)
                VALUES (?, ?, ?, 2, ?, ?)
                ON CONFLICT (organization_id, document_type, period_key)
                DO UPDATE SET next_value = hms.document_sequence.next_value + 1, updated_at = EXCLUDED.updated_at
                RETURNING next_value - 1
                """).params(organizationId, documentType, periodKey, now, now).query(Long.class).single();
    }

    /**
     * Locks the counter row of {@code documentType}/{@code periodKey} until the caller's transaction ends, without
     * allocating a number, so a check can run serialized with the issuers of that counter (no row: no lock needed,
     * nothing has been issued yet).
     */
    public void lock(UUID organizationId, String documentType, String periodKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("a document counter must be locked inside the business transaction");
        }
        jdbc.sql("""
                SELECT 1 FROM hms.document_sequence
                WHERE organization_id = ? AND document_type = ? AND period_key = ? FOR UPDATE
                """).params(organizationId, documentType, periodKey).query(Integer.class).list();
    }
}
