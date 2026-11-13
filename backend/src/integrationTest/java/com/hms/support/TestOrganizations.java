package com.hms.support;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Synthetic organizations for integration tests. The organization table is outside RLS, so no context is needed. */
public final class TestOrganizations {

    private TestOrganizations() {
    }

    public static UUID create(JdbcClient jdbc) {
        UUID id = UUID.randomUUID();
        ensure(jdbc, id);
        return id;
    }

    public static UUID ensure(JdbcClient jdbc, UUID id) {
        jdbc.sql("""
                INSERT INTO hms.organization (id, name, status, created_at, updated_at)
                VALUES (?, ?, 'ACTIVE', now(), now())
                ON CONFLICT (id) DO NOTHING
                """)
                .params(id, "Demo Clinic " + id.toString().substring(0, 8))
                .update();
        return id;
    }
}
