-- [E8-2] [E8-3] Admissions and bed assignments. Design: docs/architecture/database-design.md section 20q.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE from the default privileges of V1 (ADR-0015) and no DELETE:
-- admissions end by status (DISCHARGED, CANCELLED), never by removal. A bed assignment is history: hms_app may only
-- set released_at (column-level grant), so the bed, admission and assigned_at of a stay can never be rewritten.
--
-- Invariants (domain-model.md section 5): I1 at most one open assignment per bed, I2 at most one open assignment per
-- admission, I3 at most one active (ADMITTED or DISCHARGE_INITIATED) admission per patient. Each is a partial unique
-- index; the application also locks the bed row first (lock order: bed, admission, invoice, document_sequence).

CREATE TABLE hms.admission (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    -- Allocated last in the creating transaction (after the bed and patient checks, so the document_sequence row
    -- lock is held briefly); NULL only inside that transaction, never after it commits.
    admission_no         text,
    patient_id           uuid        NOT NULL,
    admitting_doctor_id  uuid        NOT NULL,
    source_encounter_id  uuid,
    admission_diagnosis  text        NOT NULL,
    status               text        NOT NULL DEFAULT 'ADMITTED',
    admitted_at          timestamptz NOT NULL,
    planned_discharge_at timestamptz,
    discharged_at        timestamptz,
    version              bigint      NOT NULL DEFAULT 0,
    created_by           uuid        NOT NULL,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT pk_admission PRIMARY KEY (id),
    CONSTRAINT uq_admission_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_admission_org_no UNIQUE (organization_id, admission_no),
    CONSTRAINT fk_admission_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_admission_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT fk_admission_doctor FOREIGN KEY (organization_id, admitting_doctor_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT fk_admission_source_encounter FOREIGN KEY (organization_id, source_encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_admission_status CHECK (status IN ('ADMITTED', 'DISCHARGE_INITIATED', 'DISCHARGED', 'CANCELLED')),
    CONSTRAINT ck_admission_diagnosis CHECK (char_length(admission_diagnosis) BETWEEN 1 AND 1000),
    CONSTRAINT ck_admission_discharged CHECK (
        (status = 'DISCHARGED') = (discharged_at IS NOT NULL)),
    CONSTRAINT ck_admission_discharged_after CHECK (discharged_at IS NULL OR discharged_at >= admitted_at),
    CONSTRAINT ck_admission_planned_after CHECK (planned_discharge_at IS NULL OR planned_discharge_at >= admitted_at)
);
-- I3: one active admission per patient.
CREATE UNIQUE INDEX uq_admission_patient_active ON hms.admission (organization_id, patient_id)
    WHERE status IN ('ADMITTED', 'DISCHARGE_INITIATED');
CREATE INDEX ix_admission_status ON hms.admission (organization_id, status, admitted_at);
CREATE INDEX ix_admission_patient ON hms.admission (organization_id, patient_id, admitted_at);

CREATE TABLE hms.bed_assignment (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    bed_id          uuid        NOT NULL,
    admission_id    uuid        NOT NULL,
    assigned_at     timestamptz NOT NULL,
    released_at     timestamptz,
    reason          text,
    CONSTRAINT pk_bed_assignment PRIMARY KEY (id),
    CONSTRAINT uq_bed_assignment_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_bed_assignment_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_bed_assignment_bed FOREIGN KEY (organization_id, bed_id) REFERENCES hms.bed (organization_id, id),
    CONSTRAINT fk_bed_assignment_admission FOREIGN KEY (organization_id, admission_id)
        REFERENCES hms.admission (organization_id, id),
    CONSTRAINT ck_bed_assignment_released CHECK (released_at IS NULL OR released_at >= assigned_at),
    CONSTRAINT ck_bed_assignment_reason CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 500)
);
-- I1: one open assignment per bed. I2: one open assignment per admission.
CREATE UNIQUE INDEX uq_bed_assignment_bed_open ON hms.bed_assignment (organization_id, bed_id)
    WHERE released_at IS NULL;
CREATE UNIQUE INDEX uq_bed_assignment_admission_open ON hms.bed_assignment (organization_id, admission_id)
    WHERE released_at IS NULL;
CREATE INDEX ix_bed_assignment_admission ON hms.bed_assignment (organization_id, admission_id, assigned_at);

REVOKE UPDATE ON hms.bed_assignment FROM hms_app;
GRANT UPDATE (released_at) ON hms.bed_assignment TO hms_app;

-- Promised by V29: an IPD invoice points at an admission of the same organization.
ALTER TABLE hms.invoice ADD CONSTRAINT fk_invoice_admission FOREIGN KEY (organization_id, admission_id)
    REFERENCES hms.admission (organization_id, id);

ALTER TABLE hms.admission ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.admission FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.admission
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.bed_assignment ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.bed_assignment FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.bed_assignment
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
