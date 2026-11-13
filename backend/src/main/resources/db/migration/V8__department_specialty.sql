-- [E2-2] Departments and specialties (organization module). Design: docs/architecture/database-design.md section 17.
-- Runs as hms_owner. Soft-deactivate only: no DELETE grant is added (default privileges give hms_app
-- SELECT/INSERT/UPDATE), so a department or specialty that practitioners/beds will reference can never vanish.
-- UNIQUE (organization_id, id) is the target of same-org composite FKs from later tables (practitioner, ward).

CREATE TABLE hms.department (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    active          boolean     NOT NULL DEFAULT true,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_department PRIMARY KEY (id),
    CONSTRAINT fk_department_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT uq_department_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_department_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_department_code CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    CONSTRAINT ck_department_name CHECK (char_length(name) BETWEEN 1 AND 100)
);

CREATE TABLE hms.specialty (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    active          boolean     NOT NULL DEFAULT true,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_specialty PRIMARY KEY (id),
    CONSTRAINT fk_specialty_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT uq_specialty_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_specialty_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_specialty_code CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    CONSTRAINT ck_specialty_name CHECK (char_length(name) BETWEEN 1 AND 100)
);

ALTER TABLE hms.department ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.department FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.department
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.specialty ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.specialty FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.specialty
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
