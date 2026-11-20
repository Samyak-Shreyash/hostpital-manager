package com.hms.billing.infrastructure;

import com.hms.billing.domain.InvoiceCalculator.InvoiceTotals;
import com.hms.billing.domain.InvoiceCalculator.LineTotals;
import com.hms.billing.domain.InvoiceLine;
import com.hms.shared.domain.Money;
import com.hms.shared.id.UuidV7Generator;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Invoices and their items (V29). Tables are under RLS; every query also filters by org. */
@Repository
public class InvoiceRepository {

    private static final String COLUMNS = "id, type, patient_id, encounter_id, admission_id, status, invoice_no,"
            + " issued_at, subtotal, discount_total, tax_total, total, version, created_at, updated_at";
    private static final String ITEM_COLUMNS = "id, service_id, description, quantity, unit_price, discount,"
            + " discount_reason, tax_rate, gross, taxable, tax, total";
    private static final String FILTER = " WHERE organization_id = :org"
            + " AND (CAST(:patient AS uuid) IS NULL OR patient_id = :patient)"
            + " AND (CAST(:status AS text) IS NULL OR status = ANY(string_to_array(:status, ',')))"
            + " AND (CAST(:from AS timestamptz) IS NULL OR created_at >= :from)"
            + " AND (CAST(:to AS timestamptz) IS NULL OR created_at < :to)";

    public record InvoiceRow(UUID id, String type, UUID patientId, UUID encounterId, UUID admissionId, String status,
            String invoiceNo, Instant issuedAt, Money subtotal, Money discountTotal, Money taxTotal, Money total,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record ItemRow(UUID id, InvoiceLine line) {
    }

    public record ListFilter(UUID patientId, String status, Instant from, Instant to) {
    }

    private final JdbcClient jdbc;
    private final UuidV7Generator ids;

    InvoiceRepository(JdbcClient jdbc, UuidV7Generator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    /** @return false when the encounter already has a live invoice (the partial unique index decides) */
    public boolean insertOpdDraft(UUID id, UUID org, UUID patientId, UUID encounterId, UUID createdBy, Instant now) {
        try {
            jdbc.sql("""
                    INSERT INTO hms.invoice (id, organization_id, type, patient_id, encounter_id, created_by,
                        created_at, updated_at)
                    VALUES (?, ?, 'OPD', ?, ?, ?, ?, ?)
                    """).params(id, org, patientId, encounterId, createdBy, Timestamp.from(now),
                    Timestamp.from(now)).update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<InvoiceRow> find(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.invoice WHERE organization_id = ? AND id = ?")
                .params(org, id).query(InvoiceRepository::map).optional();
    }

    /** The row locked until the transaction ends: state and version are decided on the locked row. */
    public Optional<InvoiceRow> lock(UUID org, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM hms.invoice WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(InvoiceRepository::map).optional();
    }

    public List<InvoiceRow> list(UUID org, ListFilter filter, int limit, long offset) {
        return filtered("SELECT " + COLUMNS + " FROM hms.invoice" + FILTER
                + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset", org, filter)
                .param("limit", limit).param("offset", offset).query(InvoiceRepository::map).list();
    }

    public long count(UUID org, ListFilter filter) {
        return filtered("SELECT count(*) FROM hms.invoice" + FILTER, org, filter).query(Long.class).single();
    }

    private JdbcClient.StatementSpec filtered(String sql, UUID org, ListFilter f) {
        return jdbc.sql(sql).param("org", org).param("patient", f.patientId()).param("status", f.status())
                .param("from", f.from() == null ? null : Timestamp.from(f.from()))
                .param("to", f.to() == null ? null : Timestamp.from(f.to()));
    }

    public List<ItemRow> items(UUID org, UUID invoiceId) {
        return jdbc.sql("SELECT " + ITEM_COLUMNS + " FROM hms.invoice_item WHERE organization_id = ?"
                + " AND invoice_id = ? ORDER BY position").params(org, invoiceId)
                .query(InvoiceRepository::mapItem).list();
    }

    /** Replaces every item of a DRAFT and stores the new totals, bumping the version. */
    public void replaceItems(UUID org, UUID invoiceId, List<InvoiceLine> lines, InvoiceTotals totals, Instant now) {
        jdbc.sql("DELETE FROM hms.invoice_item WHERE organization_id = ? AND invoice_id = ?")
                .params(org, invoiceId).update();
        for (int i = 0; i < lines.size(); i++) {
            InvoiceLine l = lines.get(i);
            LineTotals t = l.totals();
            jdbc.sql("""
                    INSERT INTO hms.invoice_item (id, organization_id, invoice_id, position, service_id, description,
                        quantity, unit_price, discount, discount_reason, tax_rate, gross, taxable, tax, total,
                        created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(ids.next(), org, invoiceId, i, l.serviceId(), l.description(), l.quantity(),
                    l.unitPrice().amount(), t.discount().amount(), l.discountReason(), l.taxRate(),
                    t.gross().amount(), t.taxable().amount(), t.tax().amount(), t.total().amount(),
                    Timestamp.from(now)).update();
        }
        jdbc.sql("""
                UPDATE hms.invoice SET subtotal = ?, discount_total = ?, tax_total = ?, total = ?,
                    version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ?
                """).params(totals.subtotal().amount(), totals.discountTotal().amount(), totals.taxTotal().amount(),
                totals.total().amount(), Timestamp.from(now), org, invoiceId).update();
    }

    /** DRAFT to ISSUED on the locked row: number, issue time and issuer, recomputed totals; bumps the version. */
    public void markIssued(UUID org, UUID id, String invoiceNo, UUID issuedBy, InvoiceTotals totals, Instant now) {
        jdbc.sql("""
                UPDATE hms.invoice SET status = 'ISSUED', invoice_no = ?, issued_at = ?, issued_by = ?, subtotal = ?,
                    discount_total = ?, tax_total = ?, total = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ?
                """).params(invoiceNo, Timestamp.from(now), issuedBy, totals.subtotal().amount(),
                totals.discountTotal().amount(), totals.taxTotal().amount(), totals.total().amount(),
                Timestamp.from(now), org, id).update();
    }

    /** Sets the status of the locked row, bumping the version. */
    public void setStatus(UUID org, UUID id, String status, Instant now) {
        jdbc.sql("UPDATE hms.invoice SET status = ?, version = version + 1, updated_at = ?"
                + " WHERE organization_id = ? AND id = ?").params(status, Timestamp.from(now), org, id).update();
    }

    private static InvoiceRow map(ResultSet rs, int row) throws SQLException {
        return new InvoiceRow(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getObject("patient_id", UUID.class), rs.getObject("encounter_id", UUID.class),
                rs.getObject("admission_id", UUID.class), rs.getString("status"), rs.getString("invoice_no"),
                instant(rs, "issued_at"), money(rs, "subtotal"), money(rs, "discount_total"), money(rs, "tax_total"),
                money(rs, "total"), rs.getLong("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static ItemRow mapItem(ResultSet rs, int row) throws SQLException {
        LineTotals totals = new LineTotals(money(rs, "gross"), money(rs, "discount"), money(rs, "taxable"),
                money(rs, "tax"), money(rs, "total"));
        return new ItemRow(rs.getObject("id", UUID.class), new InvoiceLine(rs.getObject("service_id", UUID.class),
                rs.getString("description"), rs.getBigDecimal("quantity"), money(rs, "unit_price"),
                totals.discount(), rs.getString("discount_reason"), rs.getBigDecimal("tax_rate"), totals));
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        return Money.of(rs.getBigDecimal(column));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
