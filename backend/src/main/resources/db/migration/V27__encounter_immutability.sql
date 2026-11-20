-- [E6-2] Database-level immutability of a FINALIZED encounter (domain rules, CLAUDE.md section 5; database-design.md
-- section 20h). Runs as hms_owner. V26 guarded only the encounter row (tg_encounter_immutable); the child tables were
-- protected by the service alone. This one function, hms.guard_finalized_encounter(), replaces that guard and covers
-- the encounter row and every child table, so a bug or a direct SQL session cannot rewrite a signed record.
--
-- Rules (SQLSTATE HM001, mapped to 409 ENCOUNTER_FINALIZED by ProblemDetailsHandler):
--   * encounter: no UPDATE or DELETE once OLD.status = 'FINALIZED' (the OPEN -> FINALIZED update itself is allowed).
--   * encounter_observation, clinical_note, encounter_diagnosis, treatment_plan, follow_up: no INSERT, UPDATE
--     (including the replaced_at flag the service uses to replace a set) or DELETE while the parent is FINALIZED,
--     except the INSERT of a clinical_note of kind ADDENDUM. An existing addendum is itself immutable.
-- The parent lookup runs under the caller's RLS context like the statement that fired the trigger, so it sees the
-- parent whenever the child row is visible at all.

DROP TRIGGER tg_encounter_immutable ON hms.encounter;
DROP FUNCTION hms.encounter_immutable_once_finalized();

CREATE FUNCTION hms.guard_finalized_encounter() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    IF TG_TABLE_NAME = 'encounter' THEN
        IF OLD.status = 'FINALIZED' THEN
            RAISE EXCEPTION 'a finalized encounter cannot be changed' USING ERRCODE = 'HM001';
        END IF;
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;

    IF TG_TABLE_NAME = 'clinical_note' AND TG_OP = 'INSERT' THEN
        -- Nested: PL/pgSQL does not short-circuit, and NEW.kind exists on this table only.
        IF NEW.kind = 'ADDENDUM' THEN
            RETURN NEW;
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_status FROM hms.encounter
        WHERE organization_id = OLD.organization_id AND id = OLD.encounter_id;
    ELSE
        SELECT status INTO parent_status FROM hms.encounter
        WHERE organization_id = NEW.organization_id AND id = NEW.encounter_id;
    END IF;
    IF parent_status = 'FINALIZED' THEN
        RAISE EXCEPTION 'a finalized encounter cannot be changed' USING ERRCODE = 'HM001';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_encounter_guard BEFORE UPDATE OR DELETE ON hms.encounter
    FOR EACH ROW EXECUTE FUNCTION hms.guard_finalized_encounter();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['encounter_observation', 'clinical_note', 'encounter_diagnosis', 'treatment_plan',
                             'follow_up'] LOOP
        EXECUTE format('CREATE TRIGGER tg_%s_guard BEFORE INSERT OR UPDATE OR DELETE ON hms.%I
            FOR EACH ROW EXECUTE FUNCTION hms.guard_finalized_encounter()', t, t);
    END LOOP;
END
$$;
