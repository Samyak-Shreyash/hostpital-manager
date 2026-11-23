package com.hms.reporting.infrastructure;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Runs report SQL (named parameters) and returns rows with JSON-ready values in column order. */
@Repository
public class ReportRepository {

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> query(String sql, Map<String, ?> params) {
        return jdbc.sql(sql).params(params).query((rs, n) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            var meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                row.put(meta.getColumnLabel(i), convert(rs.getObject(i)));
            }
            return row;
        }).list();
    }

    private static Object convert(Object v) {
        return switch (v) {
            case BigDecimal b -> b.toPlainString();
            case java.sql.Date d -> d.toLocalDate().toString();
            case Timestamp t -> t.toInstant().toString();
            case UUID u -> u.toString();
            case null, default -> v;
        };
    }
}
