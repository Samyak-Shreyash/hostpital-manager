package com.hms.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need PostgreSQL with the real database roles (ADR-0015):
 * Flyway migrates as {@code hms_owner}, the application connects as {@code hms_app}.
 * One container is shared by all subclasses and stopped when the JVM exits.
 */
public abstract class PostgresIT {

    public static final String OWNER = "hms_owner";
    public static final String OWNER_PASSWORD = "owner-test-only";
    public static final String APP = "hms_app";
    public static final String APP_PASSWORD = "app-test-only";

    protected static final PostgreSQLContainer postgres =
            new PostgreSQLContainer("postgres:17-alpine").withInitScript("db/init-roles.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    }
}
