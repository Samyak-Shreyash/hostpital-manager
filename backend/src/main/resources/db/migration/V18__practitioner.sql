-- [E4-1] Doctor registration (practitioner module). Design: docs/architecture/database-design.md section 20.
-- Runs as hms_owner. A practitioner is never deleted (lifecycle is `status`); no DELETE grant on hms.practitioner.
-- The specialty/department join rows are replace-set mappings without clinical or financial content, so hms_app
-- may DELETE them (as V6 does for user_role / role_permission); every change is audited on the practitioner.
-- Same-org composite FKs make a cross-organization user, specialty or department reference impossible at the DB.

CREATE TABLE hms.practitioner (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    user_id              uuid        NOT NULL,
    full_name            text        NOT NULL,
    registration_no      text        NOT NULL,
    registration_council text,
    qualifications       text[]      NOT NULL DEFAULT '{}',
    default_slot_minutes integer     NOT NULL DEFAULT 15,
    status               text        NOT NULL DEFAULT 'ACTIVE',
    created_by           uuid        NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT pk_practitioner PRIMARY KEY (id),
    CONSTRAINT fk_practitioner_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_practitioner_user FOREIGN KEY (organization_id, user_id)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT uq_practitioner_org_id UNIQUE (organization_id, id),
    -- One practitioner per user (domain-model: User 1:0..1 Practitioner). User ids are global, so no org column.
    CONSTRAINT uq_practitioner_user UNIQUE (user_id),
    CONSTRAINT ck_practitioner_full_name CHECK (char_length(full_name) BETWEEN 2 AND 120),
    CONSTRAINT ck_practitioner_registration_no CHECK (registration_no ~ '^[A-Z0-9][A-Z0-9/.-]{0,49}$'),
    CONSTRAINT ck_practitioner_registration_council
        CHECK (registration_council IS NULL OR char_length(registration_council) BETWEEN 1 AND 100),
    CONSTRAINT ck_practitioner_qualifications CHECK (cardinality(qualifications) <= 10),
    CONSTRAINT ck_practitioner_slot CHECK (default_slot_minutes BETWEEN 5 AND 120),
    CONSTRAINT ck_practitioner_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
-- Registration number unique per organization (stored upper-cased by the application, re-checked above).
CREATE UNIQUE INDEX uq_practitioner_org_registration ON hms.practitioner (organization_id, registration_no);

CREATE TABLE hms.practitioner_specialty (
    organization_id uuid        NOT NULL,
    practitioner_id uuid        NOT NULL,
    specialty_id    uuid        NOT NULL,
    is_primary      boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_practitioner_specialty PRIMARY KEY (practitioner_id, specialty_id),
    CONSTRAINT fk_practitioner_specialty_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT fk_practitioner_specialty_specialty FOREIGN KEY (organization_id, specialty_id)
        REFERENCES hms.specialty (organization_id, id)
);
-- At most one primary per practitioner (immediate); "at least one" is the deferred trigger below.
CREATE UNIQUE INDEX uq_practitioner_specialty_primary ON hms.practitioner_specialty (practitioner_id)
    WHERE is_primary;
-- Many practitioners per specialty: list filter by specialty.
CREATE INDEX ix_practitioner_specialty_specialty ON hms.practitioner_specialty (organization_id, specialty_id);

CREATE TABLE hms.practitioner_department (
    organization_id uuid        NOT NULL,
    practitioner_id uuid        NOT NULL,
    department_id   uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_practitioner_department PRIMARY KEY (practitioner_id, department_id),
    CONSTRAINT fk_practitioner_department_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT fk_practitioner_department_department FOREIGN KEY (organization_id, department_id)
        REFERENCES hms.department (organization_id, id)
);
CREATE INDEX ix_practitioner_department_department ON hms.practitioner_department (organization_id, department_id);

-- "Exactly one primary specialty" (BR, domain-model Practitioner <-> Specialty). A replace-set deletes and inserts
-- rows within one transaction, so the count is legitimately 0 or 2 mid-statement; the check therefore runs as a
-- DEFERRABLE INITIALLY DEFERRED constraint trigger at commit, on every practitioner insert and every join-row
-- change, whatever code path wrote it. The application also validates up front (clear 422) and serializes writers
-- with the practitioner row lock; this trigger is the guarantee.
CREATE FUNCTION hms.check_practitioner_primary_specialty() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    pid uuid;
    primaries integer;
BEGIN
    IF TG_TABLE_NAME = 'practitioner' THEN
        pid := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        pid := OLD.practitioner_id;
    ELSE
        pid := NEW.practitioner_id;
    END IF;
    SELECT count(*) INTO primaries FROM hms.practitioner_specialty WHERE practitioner_id = pid AND is_primary;
    IF primaries <> 1 THEN
        RAISE EXCEPTION 'practitioner % must have exactly one primary specialty (has %)', pid, primaries
            USING ERRCODE = 'HM002';
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION hms.check_practitioner_primary_specialty() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hms.check_practitioner_primary_specialty() TO hms_app;

CREATE CONSTRAINT TRIGGER trg_practitioner_primary_specialty AFTER INSERT ON hms.practitioner
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hms.check_practitioner_primary_specialty();
CREATE CONSTRAINT TRIGGER trg_practitioner_specialty_primary AFTER INSERT OR UPDATE OR DELETE
    ON hms.practitioner_specialty
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hms.check_practitioner_primary_specialty();

GRANT DELETE ON hms.practitioner_specialty, hms.practitioner_department TO hms_app;

ALTER TABLE hms.practitioner ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.practitioner FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.practitioner
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.practitioner_specialty ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.practitioner_specialty FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.practitioner_specialty
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.practitioner_department ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.practitioner_department FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.practitioner_department
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
