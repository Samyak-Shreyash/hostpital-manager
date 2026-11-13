-- [E3-4] Allergies and medical history (PAT-3). Runs as hms_owner.
-- One table for both kinds. Entries are append-only: the content never changes and nothing is deleted. A wrong entry
-- is marked ENTERED_IN_ERROR (a one-way status change) and stays visible as history. The subject and detail columns
-- are clinical free text (PHI): they live here only and never in audit_event, which records field names.
CREATE TABLE hms.patient_clinical_entry (
    id                  uuid        NOT NULL,
    organization_id     uuid        NOT NULL,
    patient_id          uuid        NOT NULL,
    kind                text        NOT NULL,
    subject             text        NOT NULL, -- allergy: substance; history: condition
    detail              text,                 -- allergy: reaction; history: notes
    severity            text,                 -- allergies only
    status              text        NOT NULL DEFAULT 'ACTIVE',
    recorded_by         uuid        NOT NULL,
    created_at          timestamptz NOT NULL,
    entered_in_error_by uuid,
    entered_in_error_at timestamptz,
    CONSTRAINT pk_patient_clinical_entry PRIMARY KEY (id),
    CONSTRAINT fk_patient_clinical_entry_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT ck_patient_clinical_entry_kind CHECK (kind IN ('ALLERGY', 'HISTORY')),
    CONSTRAINT ck_patient_clinical_entry_subject CHECK (char_length(btrim(subject)) BETWEEN 1 AND 200),
    CONSTRAINT ck_patient_clinical_entry_detail CHECK (detail IS NULL OR char_length(btrim(detail)) BETWEEN 1 AND 1000),
    CONSTRAINT ck_patient_clinical_entry_severity CHECK (
        (kind = 'ALLERGY' AND severity IN ('MILD', 'MODERATE', 'SEVERE'))
        OR (kind = 'HISTORY' AND severity IS NULL)),
    CONSTRAINT ck_patient_clinical_entry_status CHECK (status IN ('ACTIVE', 'ENTERED_IN_ERROR')),
    CONSTRAINT ck_patient_clinical_entry_marked CHECK (
        (status = 'ACTIVE' AND entered_in_error_by IS NULL AND entered_in_error_at IS NULL)
        OR (status = 'ENTERED_IN_ERROR' AND entered_in_error_by IS NOT NULL AND entered_in_error_at IS NOT NULL))
);
CREATE INDEX ix_patient_clinical_entry_patient
    ON hms.patient_clinical_entry (organization_id, patient_id, kind, created_at);

ALTER TABLE hms.patient_clinical_entry ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.patient_clinical_entry FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.patient_clinical_entry
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- The runtime role may add and read rows, and may touch only the three status columns (column-level UPDATE grant).
-- It can never delete. The trigger below enforces the same rule for every role, the owner included, and makes
-- the status change one-way ACTIVE -> ENTERED_IN_ERROR.
REVOKE UPDATE, DELETE ON hms.patient_clinical_entry FROM hms_app;
GRANT UPDATE (status, entered_in_error_by, entered_in_error_at) ON hms.patient_clinical_entry TO hms_app;

CREATE FUNCTION hms.patient_clinical_entry_guard() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'patient_clinical_entry rows cannot be deleted' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.status <> 'ACTIVE' OR NEW.status <> 'ENTERED_IN_ERROR' THEN
        RAISE EXCEPTION 'patient_clinical_entry status may only change from ACTIVE to ENTERED_IN_ERROR'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.organization_id, NEW.patient_id, NEW.kind, NEW.subject, NEW.detail, NEW.severity,
        NEW.recorded_by, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.organization_id, OLD.patient_id, OLD.kind, OLD.subject, OLD.detail, OLD.severity,
        OLD.recorded_by, OLD.created_at) THEN
        RAISE EXCEPTION 'patient_clinical_entry content is immutable' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_patient_clinical_entry_guard
    BEFORE UPDATE OR DELETE ON hms.patient_clinical_entry
    FOR EACH ROW EXECUTE FUNCTION hms.patient_clinical_entry_guard();
