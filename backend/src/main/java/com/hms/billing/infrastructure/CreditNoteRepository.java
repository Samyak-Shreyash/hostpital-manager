package com.hms.billing.infrastructure;

import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code hms.credit_note} (V33): insert and read only; the table is append-only. Every query is organization-scoped. */
@Repository
public class CreditNoteRepository {

    public record CreditNoteRow(UUID id, UUID invoiceId, Money amount, String reason, String creditNoteNo,
            Instant issuedAt, UUID issuedBy) {
    }

    private final JdbcClient jdbc;

    public CreditNoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID org, CreditNoteRow c) {
        jdbc.sql("""
                INSERT INTO hms.credit_note (id, organization_id, invoice_id, amount, reason, credit_note_no,
                    issued_at, issued_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(c.id(), org, c.invoiceId(), c.amount().amount(), c.reason(), c.creditNoteNo(),
                        Timestamp.from(c.issuedAt()), c.issuedBy(), Timestamp.from(c.issuedAt()))
                .update();
    }

    /** The sum of the invoice's credit notes; call while holding the invoice row lock. */
    public Money creditedOn(UUID org, UUID invoiceId) {
        return Money.of(jdbc.sql("SELECT coalesce(sum(amount), 0) FROM hms.credit_note"
                + " WHERE organization_id = ? AND invoice_id = ?").params(org, invoiceId)
                .query(BigDecimal.class).single());
    }
}
