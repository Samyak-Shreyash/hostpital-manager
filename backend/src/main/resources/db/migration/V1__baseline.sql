-- [E0-6] Baseline: extensions, privileges, immutability function, document numbering.
-- Design: docs/architecture/database-design.md sections 3, 5, 6, 7. Roles per ADR-0015.
-- Runs as hms_owner (spring.flyway.user). Roles hms_owner and hms_app are provisioned outside Flyway.

-- Extensions (section 5): trusted on PG13+, so the database owner can create them.
CREATE EXTENSION IF NOT EXISTS btree_gist WITH SCHEMA hms;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA hms;

-- Schema and database privileges (section 3).
REVOKE ALL ON SCHEMA hms FROM PUBLIC;
GRANT USAGE ON SCHEMA hms TO hms_app;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $$
BEGIN
    EXECUTE format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database());
END
$$;

-- Defaults for every later object created by hms_owner in hms. No DELETE: tables that need it grant it explicitly.
ALTER DEFAULT PRIVILEGES FOR ROLE hms_owner IN SCHEMA hms
    GRANT SELECT, INSERT, UPDATE ON TABLES TO hms_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hms_owner IN SCHEMA hms
    GRANT USAGE, SELECT ON SEQUENCES TO hms_app;
ALTER DEFAULT PRIVILEGES FOR ROLE hms_owner IN SCHEMA hms
    GRANT EXECUTE ON FUNCTIONS TO hms_app;

-- Shared trigger function for append-only tables and immutable states (section 7).
CREATE FUNCTION hms.reject_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'immutable row: % on %.% is not allowed', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'HM001';
END;
$$;
REVOKE ALL ON FUNCTION hms.reject_modification() FROM PUBLIC;

-- Gapless business-key counters (section 6). FK to organization is added by the organization migration.
CREATE TABLE hms.document_sequence (
    organization_id uuid        NOT NULL,
    document_type   text        NOT NULL,
    period_key      text        NOT NULL,
    next_value      bigint      NOT NULL DEFAULT 1,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_document_sequence PRIMARY KEY (organization_id, document_type, period_key),
    CONSTRAINT ck_document_sequence_type
        CHECK (document_type IN ('UHID', 'ADMISSION', 'INVOICE', 'CREDIT_NOTE', 'RECEIPT')),
    CONSTRAINT ck_document_sequence_next CHECK (next_value >= 1),
    CONSTRAINT ck_document_sequence_period
        CHECK (period_key = 'ALL' OR period_key ~ '^(FY[0-9]{4}-[0-9]{2}|CY[0-9]{4})$')
);
