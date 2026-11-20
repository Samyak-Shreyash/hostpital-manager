package com.hms.payment.infrastructure;

import com.hms.shared.domain.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code hms.payment} (V32): insert and read only; the table is append-only. Every query is organization-scoped. */
@Repository
public class PaymentRepository {

    public record PaymentRow(UUID id, UUID invoiceId, Money amount, String mode, String referenceNo, String receiptNo,
            Instant receivedAt, UUID receivedBy) {
    }

    public record ListFilter(Instant from, Instant to, String mode) {
    }

    private static final String COLUMNS =
            "id, invoice_id, amount, mode, reference_no, receipt_no, received_at, received_by";
    private static final String WHERE = """
            WHERE organization_id = :org
              AND (CAST(:from AS timestamptz) IS NULL OR received_at >= :from)
              AND (CAST(:to AS timestamptz) IS NULL OR received_at < :to)
              AND (CAST(:mode AS text) IS NULL OR mode = :mode)""";

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID org, PaymentRow p) {
        jdbc.sql("""
                INSERT INTO hms.payment (id, organization_id, invoice_id, amount, mode, reference_no, receipt_no,
                    received_at, received_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(p.id(), org, p.invoiceId(), p.amount().amount(), p.mode(), p.referenceNo(), p.receiptNo(),
                        Timestamp.from(p.receivedAt()), p.receivedBy(), Timestamp.from(p.receivedAt()))
                .update();
    }

    /** The sum of the invoice's payments; call while holding the invoice row lock. */
    public Money paidOn(UUID org, UUID invoiceId) {
        return Money.of(jdbc.sql("SELECT coalesce(sum(amount), 0) FROM hms.payment"
                + " WHERE organization_id = ? AND invoice_id = ?").params(org, invoiceId)
                .query(java.math.BigDecimal.class).single());
    }

    /**
     * The sum of effective refunds (RECORDED at creation, or APPROVED later; V33) against the invoice's payments; call
     * while holding the invoice row lock. Pending and rejected refunds do not count.
     */
    public Money refundedOn(UUID org, UUID invoiceId) {
        return Money.of(jdbc.sql("""
                SELECT coalesce(sum(r.amount), 0) FROM hms.refund r
                JOIN hms.payment p ON p.organization_id = r.organization_id AND p.id = r.payment_id
                LEFT JOIN hms.refund_decision d ON d.organization_id = r.organization_id AND d.refund_id = r.id
                WHERE p.organization_id = ? AND p.invoice_id = ?
                  AND (r.initial_status = 'RECORDED' OR d.decision = 'APPROVED')""").params(org, invoiceId)
                .query(java.math.BigDecimal.class).single());
    }

    /** The payment's invoice and amount, or empty for an unknown or other-organization id. */
    public java.util.Optional<PaymentRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.payment WHERE organization_id = ? AND id = ?")
                .params(org, id).query(PaymentRepository::map).optional();
    }

    public List<PaymentRow> list(UUID org, ListFilter f, int limit, long offset) {
        return bind(jdbc.sql("SELECT " + COLUMNS + " FROM hms.payment " + WHERE
                + " ORDER BY received_at DESC, id DESC LIMIT :limit OFFSET :offset"), org, f)
                .param("limit", limit).param("offset", offset).query(PaymentRepository::map).list();
    }

    public long count(UUID org, ListFilter f) {
        return bind(jdbc.sql("SELECT count(*) FROM hms.payment " + WHERE), org, f).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID org, ListFilter f) {
        return spec.param("org", org)
                .param("from", f.from() == null ? null : Timestamp.from(f.from()))
                .param("to", f.to() == null ? null : Timestamp.from(f.to()))
                .param("mode", f.mode());
    }

    private static PaymentRow map(ResultSet rs, int row) throws SQLException {
        return new PaymentRow(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                Money.of(rs.getBigDecimal("amount")), rs.getString("mode"), rs.getString("reference_no"),
                rs.getString("receipt_no"), rs.getTimestamp("received_at").toInstant(),
                rs.getObject("received_by", UUID.class));
    }
}
