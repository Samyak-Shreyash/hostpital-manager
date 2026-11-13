package com.hms.billing.infrastructure;

import com.hms.shared.domain.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Services and their price versions (V9). Both tables are under RLS and every query also filters by org. */
@Repository
public class ServiceCatalogRepository {

    public record ServiceRow(UUID id, String code, String name, boolean active, Money currentPrice, long version,
            Instant createdAt, Instant updatedAt) {
    }

    public record PriceRow(UUID id, UUID serviceId, Money amount, Instant effectiveFrom, Instant effectiveTo,
            Instant createdAt) {
    }

    private static final String SERVICE_SELECT = """
            SELECT s.id, s.code, s.name, s.active, s.version, s.created_at, s.updated_at, p.amount AS current_amount
            FROM hms.service_catalog_item s
            LEFT JOIN hms.service_price p ON p.organization_id = s.organization_id AND p.service_id = s.id
                 AND p.effective_from <= :now AND (p.effective_to IS NULL OR :now < p.effective_to)
            """;
    private static final String ACTIVE_FILTER =
            " WHERE s.organization_id = :org AND (CAST(:active AS boolean) IS NULL OR s.active = :active)";
    private static final String PRICE_COLUMNS = "id, service_id, amount, effective_from, effective_to, created_at";
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final JdbcClient jdbc;

    ServiceCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** False when the code already exists in the organization (the unique index decides, so races are safe). */
    public boolean insertService(UUID id, UUID org, String code, String name, Instant now) {
        return jdbc.sql("""
                INSERT INTO hms.service_catalog_item (id, organization_id, code, name, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, ?, ?)
                ON CONFLICT (organization_id, code) DO NOTHING
                """).params(id, org, code, name, Timestamp.from(now), Timestamp.from(now)).update() == 1;
    }

    public Optional<ServiceRow> findService(UUID org, UUID id, Instant now) {
        return jdbc.sql(SERVICE_SELECT + " WHERE s.organization_id = :org AND s.id = :id")
                .param("org", org).param("id", id).param("now", Timestamp.from(now))
                .query(ServiceCatalogRepository::mapService).optional();
    }

    public List<ServiceRow> listServices(UUID org, Boolean active, Instant now, int limit, long offset) {
        return jdbc.sql(SERVICE_SELECT + ACTIVE_FILTER + " ORDER BY s.code LIMIT :limit OFFSET :offset")
                .param("org", org).param("active", active, Types.BOOLEAN).param("now", Timestamp.from(now))
                .param("limit", limit).param("offset", offset).query(ServiceCatalogRepository::mapService).list();
    }

    public long countServices(UUID org, Boolean active) {
        return jdbc.sql("SELECT count(*) FROM hms.service_catalog_item s" + ACTIVE_FILTER)
                .param("org", org).param("active", active, Types.BOOLEAN).query(Long.class).single();
    }

    /** Compare-and-set on {@code version}: false when the row changed since the caller read it. */
    public boolean updateService(UUID org, UUID id, long expectedVersion, String name, boolean active, Instant now) {
        return jdbc.sql("""
                UPDATE hms.service_catalog_item
                SET name = ?, active = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ? AND version = ?
                """).params(name, active, Timestamp.from(now), org, id, expectedVersion).update() == 1;
    }

    /** Row lock that serializes price changes of one service (lock order: service, then its prices). */
    public boolean lockService(UUID org, UUID id) {
        return jdbc.sql("SELECT id FROM hms.service_catalog_item WHERE organization_id = ? AND id = ? FOR UPDATE")
                .params(org, id).query(UUID.class).optional().isPresent();
    }

    /** The version with the latest start, whatever its end. */
    public Optional<PriceRow> latestPrice(UUID org, UUID serviceId) {
        return jdbc.sql("SELECT " + PRICE_COLUMNS + """
                 FROM hms.service_price WHERE organization_id = ? AND service_id = ?
                ORDER BY effective_from DESC LIMIT 1
                """).params(org, serviceId).query(ServiceCatalogRepository::mapPrice).optional();
    }

    /** The version valid at {@code at}: {@code effective_from <= at < effective_to} (open end when null). */
    public Optional<PriceRow> priceAt(UUID org, UUID serviceId, Instant at) {
        return jdbc.sql("SELECT " + PRICE_COLUMNS + """
                 FROM hms.service_price WHERE organization_id = ? AND service_id = ?
                  AND effective_from <= ? AND (effective_to IS NULL OR ? < effective_to)
                """).params(org, serviceId, Timestamp.from(at), Timestamp.from(at))
                .query(ServiceCatalogRepository::mapPrice).optional();
    }

    public List<PriceRow> listPrices(UUID org, UUID serviceId, int limit, long offset) {
        return jdbc.sql("SELECT " + PRICE_COLUMNS + """
                 FROM hms.service_price WHERE organization_id = ? AND service_id = ?
                ORDER BY effective_from DESC LIMIT ? OFFSET ?
                """).params(org, serviceId, limit, offset).query(ServiceCatalogRepository::mapPrice).list();
    }

    public long countPrices(UUID org, UUID serviceId) {
        return jdbc.sql("SELECT count(*) FROM hms.service_price WHERE organization_id = ? AND service_id = ?")
                .params(org, serviceId).query(Long.class).single();
    }

    /** Ends an open-ended version. The only UPDATE the database allows on a price. */
    public boolean closePrice(UUID org, UUID priceId, Instant effectiveTo) {
        return jdbc.sql("UPDATE hms.service_price SET effective_to = ? WHERE organization_id = ? AND id = ? "
                + "AND effective_to IS NULL").params(Timestamp.from(effectiveTo), org, priceId).update() == 1;
    }

    /**
     * Inserts an open-ended version. False when the exclusion constraint rejects it because the range overlaps
     * another version of the service (the database, not the caller's check, is the authority under concurrency).
     */
    public boolean insertPrice(UUID id, UUID org, UUID serviceId, Money amount, Instant effectiveFrom, UUID actor,
            Instant now) {
        try {
            return jdbc.sql("""
                    INSERT INTO hms.service_price
                        (id, organization_id, service_id, amount, effective_from, effective_to, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
                    """).params(id, org, serviceId, amount.amount(), Timestamp.from(effectiveFrom), actor,
                    Timestamp.from(now)).update() == 1;
        } catch (DataIntegrityViolationException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                    return false;
                }
            }
            throw e;
        }
    }

    private static ServiceRow mapService(ResultSet rs, int n) throws SQLException {
        var amount = rs.getBigDecimal("current_amount");
        return new ServiceRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getBoolean("active"), amount == null ? null : Money.of(amount), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static PriceRow mapPrice(ResultSet rs, int n) throws SQLException {
        Timestamp to = rs.getTimestamp("effective_to");
        return new PriceRow(rs.getObject("id", UUID.class), rs.getObject("service_id", UUID.class),
                Money.of(rs.getBigDecimal("amount")), rs.getTimestamp("effective_from").toInstant(),
                to == null ? null : to.toInstant(), rs.getTimestamp("created_at").toInstant());
    }
}
