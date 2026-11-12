-- [E3-1] Patient registration (patient module). Design: docs/architecture/database-design.md section 19.
-- Runs as hms_owner. No DELETE grant on any table (BR-P4: patients are never hard-deleted; lifecycle is `status`).
-- UNIQUE (organization_id, id) on patient is the target of same-org composite FKs (children here, appointments later).

CREATE TABLE hms.patient (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    uhid            text        NOT NULL,
    full_name       text        NOT NULL,
    sex             text        NOT NULL,
    date_of_birth   date        NOT NULL,
    dob_estimated   boolean     NOT NULL DEFAULT false,
    phone           text        NOT NULL,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_patient PRIMARY KEY (id),
    CONSTRAINT fk_patient_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT uq_patient_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_patient_org_uhid UNIQUE (organization_id, uhid),
    CONSTRAINT ck_patient_uhid CHECK (uhid ~ '^HMS-[0-9]{6,}$'),
    CONSTRAINT ck_patient_full_name CHECK (char_length(full_name) BETWEEN 2 AND 120),
    CONSTRAINT ck_patient_sex CHECK (sex IN ('M', 'F', 'O', 'U')),
    CONSTRAINT ck_patient_phone CHECK (phone ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT ck_patient_status CHECK (status IN ('ACTIVE', 'INACTIVE', 'MERGED', 'DECEASED'))
);
CREATE INDEX ix_patient_org_phone ON hms.patient (organization_id, phone);

-- One current address per type (partial unique index); history rows keep is_current = false.
CREATE TABLE hms.patient_address (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    patient_id      uuid        NOT NULL,
    address_type    text        NOT NULL DEFAULT 'CURRENT',
    is_current      boolean     NOT NULL DEFAULT true,
    line1           text        NOT NULL,
    line2           text,
    city            text        NOT NULL,
    state           text        NOT NULL,
    pincode         text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_patient_address PRIMARY KEY (id),
    CONSTRAINT fk_patient_address_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT ck_patient_address_type CHECK (address_type IN ('CURRENT', 'PERMANENT')),
    CONSTRAINT ck_patient_address_pincode CHECK (pincode ~ '^[0-9]{6}$'),
    CONSTRAINT ck_patient_address_line1 CHECK (char_length(line1) BETWEEN 1 AND 150),
    CONSTRAINT ck_patient_address_line2 CHECK (line2 IS NULL OR char_length(line2) BETWEEN 1 AND 150),
    CONSTRAINT ck_patient_address_city CHECK (char_length(city) BETWEEN 1 AND 80),
    CONSTRAINT ck_patient_address_state CHECK (char_length(state) BETWEEN 1 AND 80)
);
CREATE UNIQUE INDEX uq_patient_address_current ON hms.patient_address (patient_id, address_type) WHERE is_current;

CREATE TABLE hms.patient_contact (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    patient_id      uuid        NOT NULL,
    contact_type    text        NOT NULL DEFAULT 'EMERGENCY',
    name            text        NOT NULL,
    relationship    text        NOT NULL,
    phone           text        NOT NULL,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_patient_contact PRIMARY KEY (id),
    CONSTRAINT fk_patient_contact_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT ck_patient_contact_type CHECK (contact_type IN ('EMERGENCY')),
    CONSTRAINT ck_patient_contact_name CHECK (char_length(name) BETWEEN 2 AND 120),
    CONSTRAINT ck_patient_contact_relationship CHECK (char_length(relationship) BETWEEN 1 AND 50),
    CONSTRAINT ck_patient_contact_phone CHECK (phone ~ '^\+[1-9][0-9]{7,14}$')
);
CREATE INDEX ix_patient_contact_patient ON hms.patient_contact (organization_id, patient_id);

ALTER TABLE hms.patient ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.patient_address ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient_address FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient_address
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.patient_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient_contact FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient_contact
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
