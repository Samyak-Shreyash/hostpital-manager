-- [E6-3] Prescriptions with allergy warnings (OPD-7, BR-C3, domain-model.md section 5). Design: database-design.md
-- section 20i. Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE from the default privileges of V1 (ADR-0015).
--
-- A prescription belongs to an encounter by id (open or finalized; the V27 encounter guard does not apply here). It
-- is edited as a whole (items replaced) while DRAFT, becomes immutable when ISSUED, and a correction is a new DRAFT
-- version row (version_no + 1, supersedes_id) while the old one turns SUPERSEDED. Items are PHI-bearing clinical text:
-- never audited or logged.

CREATE TABLE hms.prescription (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    encounter_id     uuid        NOT NULL,
    patient_id       uuid        NOT NULL,
    status           text        NOT NULL DEFAULT 'DRAFT',
    version_no       integer     NOT NULL DEFAULT 1,
    supersedes_id    uuid,
    issued_at        timestamptz,
    issued_by        uuid,
    version          bigint      NOT NULL DEFAULT 0,
    created_by       uuid        NOT NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    CONSTRAINT pk_prescription PRIMARY KEY (id),
    CONSTRAINT uq_prescription_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_prescription_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    CONSTRAINT fk_prescription_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT fk_prescription_supersedes FOREIGN KEY (organization_id, supersedes_id)
        REFERENCES hms.prescription (organization_id, id),
    CONSTRAINT ck_prescription_status CHECK (status IN ('DRAFT', 'ISSUED', 'SUPERSEDED')),
    CONSTRAINT ck_prescription_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_prescription_issued CHECK (
        (status = 'DRAFT' AND issued_at IS NULL AND issued_by IS NULL)
        OR (status IN ('ISSUED', 'SUPERSEDED') AND issued_at IS NOT NULL AND issued_by IS NOT NULL))
);
-- At most one ISSUED prescription per encounter; and at most one open DRAFT (a revision replaces, never adds).
CREATE UNIQUE INDEX uq_prescription_issued ON hms.prescription (organization_id, encounter_id)
    WHERE status = 'ISSUED';
CREATE UNIQUE INDEX uq_prescription_draft ON hms.prescription (organization_id, encounter_id)
    WHERE status = 'DRAFT';
CREATE INDEX ix_prescription_encounter ON hms.prescription (organization_id, encounter_id, version_no);

CREATE TABLE hms.prescription_item (
    id               uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    prescription_id  uuid        NOT NULL,
    position         integer     NOT NULL,
    drug_name        text        NOT NULL,
    strength         text,
    form             text,
    route            text,
    dose             text        NOT NULL,
    frequency        text        NOT NULL,
    duration_days    integer     NOT NULL,
    instructions     text,
    created_at       timestamptz NOT NULL,
    CONSTRAINT pk_prescription_item PRIMARY KEY (id),
    CONSTRAINT fk_prescription_item_prescription FOREIGN KEY (organization_id, prescription_id)
        REFERENCES hms.prescription (organization_id, id),
    CONSTRAINT uq_prescription_item_position UNIQUE (prescription_id, position),
    CONSTRAINT ck_prescription_item_drug CHECK (char_length(drug_name) BETWEEN 1 AND 200),
    CONSTRAINT ck_prescription_item_strength CHECK (strength IS NULL OR char_length(strength) BETWEEN 1 AND 100),
    CONSTRAINT ck_prescription_item_form CHECK (form IS NULL OR char_length(form) BETWEEN 1 AND 100),
    CONSTRAINT ck_prescription_item_route CHECK (route IS NULL OR char_length(route) BETWEEN 1 AND 100),
    CONSTRAINT ck_prescription_item_dose CHECK (char_length(dose) BETWEEN 1 AND 100),
    CONSTRAINT ck_prescription_item_frequency CHECK (char_length(frequency) BETWEEN 1 AND 100),
    CONSTRAINT ck_prescription_item_duration CHECK (duration_days BETWEEN 1 AND 365),
    CONSTRAINT ck_prescription_item_instructions CHECK (
        instructions IS NULL OR char_length(instructions) BETWEEN 1 AND 1000)
);

-- Replacing the items of a DRAFT deletes the old rows; the trigger below allows that only while the parent is DRAFT.
GRANT DELETE ON hms.prescription_item TO hms_app;

-- SQLSTATE HM003 (HM001 is the finalized-encounter guard): mapped to 409 INVALID_STATE_TRANSITION.
--   * prescription: never deleted; DRAFT -> DRAFT/ISSUED only; ISSUED -> SUPERSEDED only, every business column
--     unchanged (version and updated_at may move); SUPERSEDED is final.
--   * prescription_item: INSERT/UPDATE/DELETE only while the parent is DRAFT.
CREATE FUNCTION hms.guard_prescription() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'a prescription cannot be deleted' USING ERRCODE = 'HM003';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'ISSUED') THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'ISSUED' AND NEW.status = 'SUPERSEDED'
       AND (NEW.id, NEW.organization_id, NEW.encounter_id, NEW.patient_id, NEW.version_no, NEW.supersedes_id,
            NEW.issued_at, NEW.issued_by, NEW.created_by, NEW.created_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.organization_id, OLD.encounter_id, OLD.patient_id, OLD.version_no, OLD.supersedes_id,
            OLD.issued_at, OLD.issued_by, OLD.created_by, OLD.created_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'an issued prescription is immutable' USING ERRCODE = 'HM003';
END
$$;

CREATE FUNCTION hms.guard_prescription_item() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_status FROM hms.prescription
        WHERE organization_id = OLD.organization_id AND id = OLD.prescription_id;
    ELSE
        SELECT status INTO parent_status FROM hms.prescription
        WHERE organization_id = NEW.organization_id AND id = NEW.prescription_id;
    END IF;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'the items of an issued prescription are immutable' USING ERRCODE = 'HM003';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_prescription_guard BEFORE UPDATE OR DELETE ON hms.prescription
    FOR EACH ROW EXECUTE FUNCTION hms.guard_prescription();
CREATE TRIGGER tg_prescription_item_guard BEFORE INSERT OR UPDATE OR DELETE ON hms.prescription_item
    FOR EACH ROW EXECUTE FUNCTION hms.guard_prescription_item();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['prescription', 'prescription_item'] LOOP
        EXECUTE format('ALTER TABLE hms.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE hms.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY org_isolation ON hms.%I
            USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
            WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)$p$, t);
    END LOOP;
END
$$;
