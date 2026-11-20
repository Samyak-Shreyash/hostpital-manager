package com.hms.billing.infrastructure;

import com.hms.billing.domain.Reconciliation;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads {@code hms.invoice_reconciliation_v} (V34), the cross-module read model over invoices, credit notes,
 * payments and refunds. The view is security_invoker, so row-level security applies; the organization filter is
 * explicit as well.
 */
@Repository
public class ReconciliationRepository {

    private final JdbcClient jdbc;

    public ReconciliationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every issued-or-later invoice of the organization with its derived figures. */
    public List<Reconciliation.Row> rows(UUID org) {
        return jdbc.sql("""
                SELECT id, status, total, credited, paid, refunded FROM hms.invoice_reconciliation_v
                WHERE organization_id = ? AND status NOT IN ('DRAFT', 'DISCARDED') ORDER BY id""")
                .param(org)
                .query((rs, n) -> new Reconciliation.Row(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("total"), rs.getBigDecimal("credited"), rs.getBigDecimal("paid"),
                        rs.getBigDecimal("refunded")))
                .list();
    }
}
