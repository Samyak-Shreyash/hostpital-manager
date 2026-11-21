-- [E8-1] Wards and beds (bedmanagement module). Design: docs/architecture/database-design.md section 20.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE from the default privileges of V1 (ADR-0015) and no DELETE:
-- a ward or bed is retired with status, never removed, so assignments and invoices can always point at it.
-- bed_assignment is created later ([E8-2]); UNIQUE (organization_id, id) here is the target of its same-org FKs.

CREATE TABLE hms.ward (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    department_id   uuid,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_ward PRIMARY KEY (id),
    CONSTRAINT fk_ward_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_ward_department FOREIGN KEY (organization_id, department_id)
        REFERENCES hms.department (organization_id, id),
    CONSTRAINT uq_ward_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_ward_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_ward_code CHECK (code ~ '^[A-Z0-9_]{2,20}$'),
    CONSTRAINT ck_ward_name CHECK (char_length(name) BETWEEN 1 AND 100),
    CONSTRAINT ck_ward_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE hms.bed (
    id                    uuid        NOT NULL,
    organization_id       uuid        NOT NULL,
    ward_id               uuid        NOT NULL,
    bed_no                text        NOT NULL,
    bed_class             text,
    daily_rate_service_id uuid        NOT NULL,
    status                text        NOT NULL DEFAULT 'ACTIVE',
    version               bigint      NOT NULL DEFAULT 0,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    CONSTRAINT pk_bed PRIMARY KEY (id),
    CONSTRAINT fk_bed_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_bed_ward FOREIGN KEY (organization_id, ward_id) REFERENCES hms.ward (organization_id, id),
    CONSTRAINT fk_bed_daily_rate_service FOREIGN KEY (organization_id, daily_rate_service_id)
        REFERENCES hms.service_catalog_item (organization_id, id),
    CONSTRAINT uq_bed_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_bed_org_ward_bed_no UNIQUE (organization_id, ward_id, bed_no),
    CONSTRAINT ck_bed_bed_no CHECK (char_length(bed_no) BETWEEN 1 AND 20),
    CONSTRAINT ck_bed_bed_class CHECK (bed_class IS NULL OR char_length(bed_class) BETWEEN 1 AND 50),
    CONSTRAINT ck_bed_status CHECK (status IN ('ACTIVE', 'OUT_OF_SERVICE'))
);
CREATE INDEX ix_bed_ward ON hms.bed (organization_id, ward_id, bed_no);

ALTER TABLE hms.ward ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.ward FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.ward
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.bed ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.bed FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.bed
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
