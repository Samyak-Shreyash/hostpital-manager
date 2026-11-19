-- [E6-1] Encounter recording (OPD-3..OPD-6, domain-model.md section 5). Design: database-design.md section 20g.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE (no DELETE) from the default privileges of V1 (ADR-0015).
--
-- Clinical data is never hard-deleted. A "replace" of a set (vitals, diagnoses) or of a note marks the old rows
-- replaced_at and inserts the new ones, so the current set is the rows with replaced_at IS NULL and the earlier
-- ones stay as history. Free text (chief complaint, notes, diagnosis descriptions, plan, instructions) is PHI: it
-- is never audited or logged, only the field names are.
-- Finalize and addenda arrive with [E6-2]; the trigger below already makes a FINALIZED encounter row immutable.

CREATE TABLE hms.encounter (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    patient_id       uuid        NOT NULL,
    practitioner_id  uuid        NOT NULL,
    appointment_id   uuid,
    status           text        NOT NULL DEFAULT 'OPEN',
    chief_complaint  text,
    version          bigint      NOT NULL DEFAULT 0,
    started_at       timestamptz NOT NULL,
    finalized_at     timestamptz,
    finalized_by     uuid,
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_encounter PRIMARY KEY (id),
    CONSTRAINT uq_encounter_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_encounter_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT fk_encounter_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT fk_encounter_appointment FOREIGN KEY (organization_id, appointment_id)
        REFERENCES hms.appointment (organization_id, id),
    -- One encounter per appointment (NULLs, the ad hoc encounters, do not collide).
    CONSTRAINT uq_encounter_appointment UNIQUE (appointment_id),
    CONSTRAINT ck_encounter_status CHECK (status IN ('OPEN', 'FINALIZED')),
    CONSTRAINT ck_encounter_finalized CHECK (
        (status = 'OPEN' AND finalized_at IS NULL AND finalized_by IS NULL)
        OR (status = 'FINALIZED' AND finalized_at IS NOT NULL AND finalized_by IS NOT NULL)),
    CONSTRAINT ck_encounter_complaint CHECK (chief_complaint IS NULL OR char_length(chief_complaint) BETWEEN 1 AND 1000)
);
CREATE INDEX ix_encounter_patient_started ON hms.encounter (organization_id, patient_id, started_at DESC);

-- A finalized encounter is immutable (domain rules, CLAUDE.md section 5): corrections are addenda.
CREATE FUNCTION hms.encounter_immutable_once_finalized() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'FINALIZED' THEN
        RAISE EXCEPTION 'a finalized encounter cannot be changed' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_encounter_immutable BEFORE UPDATE ON hms.encounter
    FOR EACH ROW EXECUTE FUNCTION hms.encounter_immutable_once_finalized();

CREATE TABLE hms.encounter_observation (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    encounter_id     uuid          NOT NULL,
    kind             text          NOT NULL,
    value            numeric(7, 2) NOT NULL,
    unit             text          NOT NULL,
    recorded_by      uuid          NOT NULL,
    created_at       timestamptz   NOT NULL,
    replaced_at      timestamptz,
    CONSTRAINT pk_encounter_observation PRIMARY KEY (id),
    CONSTRAINT fk_encounter_observation_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_encounter_observation_kind CHECK (
        kind IN ('BP_SYS', 'BP_DIA', 'PULSE', 'TEMP_C', 'SPO2', 'RESP_RATE', 'WEIGHT_KG', 'HEIGHT_CM')),
    CONSTRAINT ck_encounter_observation_value CHECK (value >= 0),
    CONSTRAINT ck_encounter_observation_unit CHECK (char_length(unit) BETWEEN 1 AND 16)
);
CREATE UNIQUE INDEX uq_encounter_observation_current ON hms.encounter_observation (encounter_id, kind)
    WHERE replaced_at IS NULL;

CREATE TABLE hms.clinical_note (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    encounter_id     uuid        NOT NULL,
    kind             text        NOT NULL,
    text             text        NOT NULL,
    author_id        uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    replaced_at      timestamptz,
    CONSTRAINT pk_clinical_note PRIMARY KEY (id),
    CONSTRAINT fk_clinical_note_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_clinical_note_kind CHECK (kind IN ('SYMPTOMS', 'EXAMINATION', 'ASSESSMENT', 'ADDENDUM')),
    CONSTRAINT ck_clinical_note_text CHECK (char_length(text) BETWEEN 1 AND 10000)
);
-- One current note per kind, except addenda, which accumulate ([E6-2]).
CREATE UNIQUE INDEX uq_clinical_note_current ON hms.clinical_note (encounter_id, kind)
    WHERE replaced_at IS NULL AND kind <> 'ADDENDUM';
CREATE INDEX ix_clinical_note_encounter ON hms.clinical_note (encounter_id, created_at);

CREATE TABLE hms.encounter_diagnosis (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    encounter_id     uuid        NOT NULL,
    code             text,
    description      text        NOT NULL,
    is_primary       boolean     NOT NULL DEFAULT false,
    position         integer     NOT NULL,
    recorded_by      uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    replaced_at      timestamptz,
    CONSTRAINT pk_encounter_diagnosis PRIMARY KEY (id),
    CONSTRAINT fk_encounter_diagnosis_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_encounter_diagnosis_code CHECK (code IS NULL OR char_length(code) BETWEEN 1 AND 20),
    CONSTRAINT ck_encounter_diagnosis_description CHECK (char_length(description) BETWEEN 1 AND 500)
);
CREATE UNIQUE INDEX uq_encounter_diagnosis_primary ON hms.encounter_diagnosis (encounter_id)
    WHERE is_primary AND replaced_at IS NULL;
CREATE INDEX ix_encounter_diagnosis_encounter ON hms.encounter_diagnosis (encounter_id, position);

-- One plan and one follow-up per encounter, rewritten in place while the encounter is OPEN.
CREATE TABLE hms.treatment_plan (
    organization_id  uuid        NOT NULL,
    encounter_id     uuid        NOT NULL,
    text             text        NOT NULL,
    updated_by       uuid        NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_treatment_plan PRIMARY KEY (encounter_id),
    CONSTRAINT fk_treatment_plan_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_treatment_plan_text CHECK (char_length(text) BETWEEN 1 AND 10000)
);

CREATE TABLE hms.follow_up (
    organization_id  uuid        NOT NULL,
    encounter_id     uuid        NOT NULL,
    follow_up_date   date        NOT NULL,
    instructions     text,
    updated_by       uuid        NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_follow_up PRIMARY KEY (encounter_id),
    CONSTRAINT fk_follow_up_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT ck_follow_up_instructions CHECK (instructions IS NULL OR char_length(instructions) BETWEEN 1 AND 2000)
);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['encounter', 'encounter_observation', 'clinical_note', 'encounter_diagnosis',
                             'treatment_plan', 'follow_up'] LOOP
        EXECUTE format('ALTER TABLE hms.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE hms.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY org_isolation ON hms.%I
            USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
            WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)$p$, t);
    END LOOP;
END
$$;

-- Role templates: a DOCTOR edits the encounters of their own practitioner record only; encounter:update-any (edit
-- another doctor's encounter) is for a custom role. DOCTOR also gets observation:write (vitals).
-- Built-in roles are identified by their template code, as V11/V21 do; per organization under forced RLS.
DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);

        DELETE FROM hms.role_permission rp
        USING hms.role r
        WHERE rp.organization_id = org AND r.organization_id = org AND rp.role_id = r.id
          AND r.code = 'DOCTOR' AND rp.permission_code = 'encounter:update-any';

        -- DOCTOR records vitals too (PUT observations needs observation:write).
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, id, 'observation:write' FROM hms.role
        WHERE organization_id = org AND code = 'DOCTOR'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
