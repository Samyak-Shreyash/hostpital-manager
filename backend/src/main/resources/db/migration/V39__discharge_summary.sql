-- [E8-7] Discharge summary and its medications. Design: docs/architecture/database-design.md section 20s.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE from the default privileges of V1 (ADR-0015). A summary is never
-- deleted; its medication list is replaced as a whole on save, so hms_app may DELETE discharge_medication rows (only
-- while the admission is not DISCHARGED: see the guard below).
--
-- The planned discharge instant is stored on hms.admission.planned_discharge_at (V37), not here: it is the FINAL
-- invoice's charge end (ChargeSource.AdmissionInfo.chargeEndAt, ruling R6).
--
-- Ruling R7: once the admission is DISCHARGED the summary and its medications are immutable. The guard raises its own
-- SQLSTATE HM006 (mapped to 409 IMMUTABLE_RECORD) on INSERT/UPDATE/DELETE and fails closed like V35: a parent row that
-- is not found (missing, or not visible under the caller's RLS context) refuses the change.

CREATE TABLE hms.discharge_summary (
    id                     uuid        NOT NULL,
    organization_id        uuid        NOT NULL,
    admission_id           uuid        NOT NULL,
    final_diagnosis        text        NOT NULL,
    course_in_hospital     text,
    procedures             text,
    condition_at_discharge text        NOT NULL,
    follow_up              text,
    version                bigint      NOT NULL DEFAULT 0,
    created_by             uuid        NOT NULL,
    created_at             timestamptz NOT NULL,
    updated_by             uuid        NOT NULL,
    updated_at             timestamptz NOT NULL,
    CONSTRAINT pk_discharge_summary PRIMARY KEY (id),
    CONSTRAINT uq_discharge_summary_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_discharge_summary_admission UNIQUE (organization_id, admission_id),
    CONSTRAINT fk_discharge_summary_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_discharge_summary_admission FOREIGN KEY (organization_id, admission_id)
        REFERENCES hms.admission (organization_id, id),
    CONSTRAINT ck_discharge_summary_final_diagnosis CHECK (char_length(final_diagnosis) BETWEEN 1 AND 2000),
    CONSTRAINT ck_discharge_summary_condition CHECK (char_length(condition_at_discharge) BETWEEN 1 AND 2000),
    CONSTRAINT ck_discharge_summary_course CHECK (course_in_hospital IS NULL OR char_length(course_in_hospital) <= 10000),
    CONSTRAINT ck_discharge_summary_procedures CHECK (procedures IS NULL OR char_length(procedures) <= 4000),
    CONSTRAINT ck_discharge_summary_follow_up CHECK (follow_up IS NULL OR char_length(follow_up) <= 2000)
);

CREATE TABLE hms.discharge_medication (
    id              uuid    NOT NULL,
    organization_id uuid    NOT NULL,
    summary_id      uuid    NOT NULL,
    position        integer NOT NULL,
    name            text    NOT NULL,
    dose            text,
    frequency       text,
    duration        text,
    CONSTRAINT pk_discharge_medication PRIMARY KEY (id),
    CONSTRAINT uq_discharge_medication_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_discharge_medication_position UNIQUE (organization_id, summary_id, position),
    CONSTRAINT fk_discharge_medication_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_discharge_medication_summary FOREIGN KEY (organization_id, summary_id)
        REFERENCES hms.discharge_summary (organization_id, id),
    CONSTRAINT ck_discharge_medication_position CHECK (position >= 0),
    CONSTRAINT ck_discharge_medication_name CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT ck_discharge_medication_dose CHECK (dose IS NULL OR char_length(dose) <= 100),
    CONSTRAINT ck_discharge_medication_frequency CHECK (frequency IS NULL OR char_length(frequency) <= 100),
    CONSTRAINT ck_discharge_medication_duration CHECK (duration IS NULL OR char_length(duration) <= 100)
);

GRANT DELETE ON hms.discharge_medication TO hms_app;

CREATE FUNCTION hms.guard_discharged_summary() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_org            uuid;
    v_summary        uuid;
    v_admission      uuid;
    parent_status  text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_org := OLD.organization_id;
    ELSE
        v_org := NEW.organization_id;
    END IF;

    IF TG_TABLE_NAME = 'discharge_summary' THEN
        IF TG_OP = 'DELETE' THEN
            v_admission := OLD.admission_id;
        ELSE
            v_admission := NEW.admission_id;
        END IF;
        -- An UPDATE that moves the summary to another admission is checked against the old one too.
        IF TG_OP = 'UPDATE' AND OLD.admission_id <> NEW.admission_id THEN
            SELECT status INTO parent_status FROM hms.admission
            WHERE organization_id = OLD.organization_id AND id = OLD.admission_id;
            IF parent_status IS NULL OR parent_status = 'DISCHARGED' THEN
                RAISE EXCEPTION 'the discharge summary of a discharged admission is immutable' USING ERRCODE = 'HM006';
            END IF;
        END IF;
    ELSE
        IF TG_OP = 'DELETE' THEN
            v_summary := OLD.summary_id;
        ELSE
            v_summary := NEW.summary_id;
        END IF;
        SELECT admission_id INTO v_admission FROM hms.discharge_summary
        WHERE organization_id = v_org AND id = v_summary;
        IF v_admission IS NULL THEN
            RAISE EXCEPTION 'the parent discharge summary was not found' USING ERRCODE = 'HM006';
        END IF;
        IF TG_OP = 'UPDATE' AND OLD.summary_id <> NEW.summary_id THEN
            RAISE EXCEPTION 'a discharge medication cannot move to another summary' USING ERRCODE = 'HM006';
        END IF;
    END IF;

    SELECT status INTO parent_status FROM hms.admission WHERE organization_id = v_org AND id = v_admission;
    IF parent_status IS NULL THEN
        RAISE EXCEPTION 'the parent admission was not found' USING ERRCODE = 'HM006';
    END IF;
    IF parent_status = 'DISCHARGED' THEN
        RAISE EXCEPTION 'the discharge summary of a discharged admission is immutable' USING ERRCODE = 'HM006';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_discharge_summary_guard
    BEFORE INSERT OR UPDATE OR DELETE ON hms.discharge_summary
    FOR EACH ROW EXECUTE FUNCTION hms.guard_discharged_summary();
CREATE TRIGGER trg_discharge_medication_guard
    BEFORE INSERT OR UPDATE OR DELETE ON hms.discharge_medication
    FOR EACH ROW EXECUTE FUNCTION hms.guard_discharged_summary();

ALTER TABLE hms.discharge_summary ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.discharge_summary FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.discharge_summary
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.discharge_medication ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.discharge_medication FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.discharge_medication
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
