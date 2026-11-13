-- [E3-5] Patient update with change audit and optimistic locking. Runs as hms_owner.
-- Owner decision 2026-11-13 (docs/00-review-notes.md 1a): every field except the UHID is editable; changing the
-- name, date of birth (incl. dobEstimated) or sex needs patient:update-identity and a reason. Audit records field
-- names only, so the free-text reason lives here (append-only), like patient_duplicate_override (V15).

CREATE TABLE hms.patient_identity_change (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    patient_id      uuid        NOT NULL,
    changed_fields  text[]      NOT NULL,
    reason          text        NOT NULL,
    changed_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_patient_identity_change PRIMARY KEY (id),
    CONSTRAINT fk_patient_identity_change_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT ck_patient_identity_change_reason CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500),
    CONSTRAINT ck_patient_identity_change_fields CHECK (cardinality(changed_fields) BETWEEN 1 AND 10)
);
CREATE INDEX ix_patient_identity_change_patient ON hms.patient_identity_change (organization_id, patient_id);

ALTER TABLE hms.patient_identity_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient_identity_change FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient_identity_change
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- Immutable record: the runtime role may only add and read rows.
REVOKE UPDATE, DELETE ON hms.patient_identity_change FROM hms_app;

-- New permission patient:update-identity, granted to every role that holds patient:update (the default
-- RECEPTIONIST template gets it through patient:*). Idempotent, per organization under forced RLS, as V12/V15 do.
INSERT INTO hms.permission (code) VALUES ('patient:update-identity') ON CONFLICT DO NOTHING;

DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, role_id, 'patient:update-identity' FROM hms.role_permission
        WHERE organization_id = org AND permission_code = 'patient:update'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
