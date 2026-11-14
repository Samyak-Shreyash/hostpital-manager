-- [E3-3] Duplicate detection (BR-P3) and the audited "not a duplicate" override. Runs as hms_owner.
-- Owner decision 2026-11-13 (docs/00-review-notes.md 1a): a candidate has the same date of birth and trigram
-- similarity >= 0.45, or the same normalized phone and similarity >= 0.30. The thresholds are organization settings.

ALTER TABLE hms.organization
    ADD COLUMN duplicate_dob_threshold   numeric(3, 2) NOT NULL DEFAULT 0.45,
    ADD COLUMN duplicate_phone_threshold numeric(3, 2) NOT NULL DEFAULT 0.30,
    ADD CONSTRAINT ck_organization_duplicate_dob_threshold
        CHECK (duplicate_dob_threshold > 0 AND duplicate_dob_threshold <= 1),
    ADD CONSTRAINT ck_organization_duplicate_phone_threshold
        CHECK (duplicate_phone_threshold > 0 AND duplicate_phone_threshold <= 1);

-- The DOB arm of the candidate query (the phone arm uses ix_patient_org_phone from V10).
CREATE INDEX ix_patient_org_dob ON hms.patient (organization_id, date_of_birth);

-- One row per confirmed override: the new patient, the candidates the user saw and the reason given. The reason is
-- free text, so it lives here and never in audit_event (which records field names only). Append-only.
CREATE TABLE hms.patient_duplicate_override (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    patient_id      uuid        NOT NULL,
    candidate_ids   uuid[]      NOT NULL,
    reason          text        NOT NULL,
    overridden_by   uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_patient_duplicate_override PRIMARY KEY (id),
    CONSTRAINT fk_patient_duplicate_override_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT ck_patient_duplicate_override_reason CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500),
    CONSTRAINT ck_patient_duplicate_override_candidates CHECK (cardinality(candidate_ids) BETWEEN 1 AND 50)
);
CREATE INDEX ix_patient_duplicate_override_patient ON hms.patient_duplicate_override (organization_id, patient_id);

ALTER TABLE hms.patient_duplicate_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient_duplicate_override FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient_duplicate_override
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- Immutable record: the runtime role may only add and read rows.
REVOKE UPDATE, DELETE ON hms.patient_duplicate_override FROM hms_app;

-- New permission patient:override-duplicate, granted to the roles that register patients (holders of
-- patient:create; the default RECEPTIONIST template gets it through patient:*). Idempotent, per organization under
-- forced RLS, as V12 does.
INSERT INTO hms.permission (code) VALUES ('patient:override-duplicate') ON CONFLICT DO NOTHING;

DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, role_id, 'patient:override-duplicate' FROM hms.role_permission
        WHERE organization_id = org AND permission_code = 'patient:create'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
