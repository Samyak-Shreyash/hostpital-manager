-- Final M3 review fixes (forward-only; V27 and V28 are not edited).
--   1. The finalized-encounter guard raises its own SQLSTATE HM005 (mapped to 409 ENCOUNTER_FINALIZED). HM001 stays the
--      generic immutable-row code of hms.reject_modification() and the service-price guard (409 IMMUTABLE_RECORD).
--   2. Both child guards fail closed: when the parent encounter / prescription row is not found (missing, or not
--      visible under the caller's RLS context) the change is refused instead of allowed.
-- The ADDENDUM exception for clinical_note INSERTs is kept. Runs as hms_owner; the triggers already exist (V27, V28).

CREATE OR REPLACE FUNCTION hms.guard_finalized_encounter() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    IF TG_TABLE_NAME = 'encounter' THEN
        IF OLD.status = 'FINALIZED' THEN
            RAISE EXCEPTION 'a finalized encounter cannot be changed' USING ERRCODE = 'HM005';
        END IF;
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_status FROM hms.encounter
        WHERE organization_id = OLD.organization_id AND id = OLD.encounter_id;
    ELSE
        SELECT status INTO parent_status FROM hms.encounter
        WHERE organization_id = NEW.organization_id AND id = NEW.encounter_id;
    END IF;
    IF parent_status IS NULL THEN
        RAISE EXCEPTION 'the parent encounter was not found' USING ERRCODE = 'HM005';
    END IF;

    IF TG_TABLE_NAME = 'clinical_note' AND TG_OP = 'INSERT' THEN
        -- Nested: PL/pgSQL does not short-circuit, and NEW.kind exists on this table only.
        IF NEW.kind = 'ADDENDUM' THEN
            RETURN NEW;
        END IF;
    END IF;

    IF parent_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'a finalized encounter cannot be changed' USING ERRCODE = 'HM005';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hms.guard_prescription_item() RETURNS trigger
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
    IF parent_status IS NULL THEN
        RAISE EXCEPTION 'the parent prescription was not found' USING ERRCODE = 'HM003';
    END IF;
    IF parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'the items of an issued prescription are immutable' USING ERRCODE = 'HM003';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;
