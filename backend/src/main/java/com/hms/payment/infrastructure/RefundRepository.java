package com.hms.payment.infrastructure;

import com.hms.shared.domain.Money;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code hms.refund} and {@code hms.refund_decision} (V33): insert and read only; both are append-only. A refund's
 * effective status is derived from its initial status and its decision, if any. Every query is organization-scoped.
 */
@Repository
public class RefundRepository {

    public record RefundRow(UUID id, UUID paymentId, Money amount, String mode, String reason, String refundNo,
            String initialStatus, Instant requestedAt, UUID requestedBy) {
    }

    /** A refund with its derived status and decision, if decided. */
    public record RefundState(RefundRow refund, String decision, Instant decidedAt, UUID decidedBy) {
    }

    private final JdbcClient jdbc;

    public RefundRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID org, RefundRow r) {
        jdbc.sql("""
                INSERT INTO hms.refund (id, organization_id, payment_id, amount, mode, reason, refund_no,
                    initial_status, requested_at, requested_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(r.id(), org, r.paymentId(), r.amount().amount(), r.mode(), r.reason(), r.refundNo(),
                        r.initialStatus(), Timestamp.from(r.requestedAt()), r.requestedBy(),
                        Timestamp.from(r.requestedAt()))
                .update();
    }

    public void insertDecision(UUID org, UUID id, UUID refundId, String decision, String reason, Instant at,
            UUID by) {
        jdbc.sql("""
                INSERT INTO hms.refund_decision (id, organization_id, refund_id, decision, reason, decided_at,
                    decided_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(id, org, refundId, decision, reason, Timestamp.from(at), by, Timestamp.from(at)).update();
    }

    /** The sum of the payment's refunds that are not rejected (pending ones reserve their amount). */
    public Money reservedOn(UUID org, UUID paymentId) {
        return Money.of(jdbc.sql("""
                SELECT coalesce(sum(r.amount), 0) FROM hms.refund r
                LEFT JOIN hms.refund_decision d ON d.organization_id = r.organization_id AND d.refund_id = r.id
                WHERE r.organization_id = ? AND r.payment_id = ? AND d.decision IS DISTINCT FROM 'REJECTED'""")
                .params(org, paymentId).query(BigDecimal.class).single());
    }

    public Optional<RefundState> find(UUID org, UUID id) {
        return jdbc.sql("""
                SELECT r.id, r.payment_id, r.amount, r.mode, r.reason, r.refund_no, r.initial_status, r.requested_at,
                    r.requested_by, d.decision, d.decided_at, d.decided_by
                FROM hms.refund r
                LEFT JOIN hms.refund_decision d ON d.organization_id = r.organization_id AND d.refund_id = r.id
                WHERE r.organization_id = ? AND r.id = ?""")
                .params(org, id).query(RefundRepository::map).optional();
    }

    private static RefundState map(ResultSet rs, int row) throws SQLException {
        RefundRow refund = new RefundRow(rs.getObject("id", UUID.class), rs.getObject("payment_id", UUID.class),
                Money.of(rs.getBigDecimal("amount")), rs.getString("mode"), rs.getString("reason"),
                rs.getString("refund_no"), rs.getString("initial_status"),
                rs.getTimestamp("requested_at").toInstant(), rs.getObject("requested_by", UUID.class));
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return new RefundState(refund, rs.getString("decision"), decidedAt == null ? null : decidedAt.toInstant(),
                rs.getObject("decided_by", UUID.class));
    }
}
