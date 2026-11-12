-- [E1-3] Authentication: app users with lockout state, and Spring Session JDBC tables (ADR-0005).
-- Design: docs/architecture/database-design.md section 12. Runs as hms_owner.
-- FK to organization is added by the organization migration ([E1-6]). Roles/permissions come with [E1-5].

CREATE TABLE hms.app_user (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    email                text        NOT NULL,
    full_name            text        NOT NULL,
    password_hash        text        NOT NULL,
    status               text        NOT NULL DEFAULT 'ACTIVE',
    must_change_password boolean     NOT NULL DEFAULT true,
    failed_login_count   integer     NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_org_id UNIQUE (organization_id, id), -- target for same-org composite FKs (section 4)
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_app_user_email CHECK (email = lower(email) AND char_length(email) BETWEEN 3 AND 254 AND email LIKE '%_@_%'),
    CONSTRAINT ck_app_user_full_name CHECK (char_length(full_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_app_user_failed_logins CHECK (failed_login_count >= 0)
);
-- Login is email-only, so email is unique across all organizations (owner decision 2026-11-12, api-design.md section 3).
CREATE UNIQUE INDEX uq_app_user_email ON hms.app_user (email);

ALTER TABLE hms.audit_event
    ADD CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_id) REFERENCES hms.app_user (id);

-- Spring Session JDBC schema (spring-session-jdbc schema-postgresql.sql), placed in hms.
-- Sessions are transient technical rows, so hms_app gets DELETE here (section 3: explicit per-table grant).
CREATE TABLE hms.spring_session (
    primary_id            char(36) NOT NULL,
    session_id            char(36) NOT NULL,
    creation_time         bigint   NOT NULL,
    last_access_time      bigint   NOT NULL,
    max_inactive_interval integer  NOT NULL,
    expiry_time           bigint   NOT NULL,
    principal_name        varchar(100),
    CONSTRAINT spring_session_pk PRIMARY KEY (primary_id)
);
CREATE UNIQUE INDEX spring_session_ix1 ON hms.spring_session (session_id);
CREATE INDEX spring_session_ix2 ON hms.spring_session (expiry_time);
CREATE INDEX spring_session_ix3 ON hms.spring_session (principal_name);

CREATE TABLE hms.spring_session_attributes (
    session_primary_id char(36)     NOT NULL,
    attribute_name     varchar(200) NOT NULL,
    attribute_bytes    bytea        NOT NULL,
    CONSTRAINT spring_session_attributes_pk PRIMARY KEY (session_primary_id, attribute_name),
    CONSTRAINT spring_session_attributes_fk FOREIGN KEY (session_primary_id)
        REFERENCES hms.spring_session (primary_id) ON DELETE CASCADE
);

GRANT DELETE ON hms.spring_session, hms.spring_session_attributes TO hms_app;
