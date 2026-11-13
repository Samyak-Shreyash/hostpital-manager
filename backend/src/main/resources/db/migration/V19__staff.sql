-- [E4-2] Staff records (practitioner module; domain-model: Staff is its own aggregate, APP_USER 1:0..1 STAFF).
-- Runs as hms_owner. A staff record is never deleted (lifecycle is `status`); no DELETE grant.
-- Owner decision (review-notes 1a): every staff record links to exactly one user login. Roles are NOT stored here:
-- they stay on the user and are assigned through the existing [E1-7] path (PATCH /api/v1/users/{id}, role:assign).
-- A user is at most one of doctor or staff: UNIQUE(user_id) here and on practitioner, plus a cross-table check
-- below that serializes on the user id, so concurrent doctor/staff links of one user cannot both commit.

CREATE TABLE hms.staff (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    user_id         uuid        NOT NULL,
    full_name       text        NOT NULL,
    staff_type      text        NOT NULL,
    designation     text,
    department_id   uuid,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_staff PRIMARY KEY (id),
    CONSTRAINT fk_staff_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT fk_staff_user FOREIGN KEY (organization_id, user_id) REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT fk_staff_department FOREIGN KEY (organization_id, department_id)
        REFERENCES hms.department (organization_id, id),
    CONSTRAINT uq_staff_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_staff_user UNIQUE (user_id),
    CONSTRAINT ck_staff_full_name CHECK (char_length(full_name) BETWEEN 2 AND 120),
    CONSTRAINT ck_staff_type CHECK (staff_type IN ('NURSE', 'RECEPTIONIST', 'ACCOUNTANT', 'LAB_TECHNICIAN',
        'PHARMACIST', 'ADMINISTRATIVE', 'OTHER')),
    CONSTRAINT ck_staff_designation CHECK (designation IS NULL OR char_length(designation) BETWEEN 1 AND 80),
    CONSTRAINT ck_staff_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE INDEX ix_staff_org_name ON hms.staff (organization_id, lower(full_name));

-- Transaction-scoped lock on one user id, shared by the application and the trigger below.
CREATE FUNCTION hms.lock_person_user_link(p_user_id uuid) RETURNS void
LANGUAGE sql AS $$ SELECT pg_advisory_xact_lock(hashtextextended('person_user_link:' || p_user_id::text, 0)) $$;
REVOKE ALL ON FUNCTION hms.lock_person_user_link(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hms.lock_person_user_link(uuid) TO hms_app;

-- Refuses a doctor for a user that is staff, and staff for a user that is a doctor (SQLSTATE 23505). The lock is
-- held until commit and each statement takes a fresh snapshot (READ COMMITTED), so the loser of a race sees the
-- winner's committed row. user_id is not updatable by the application; the trigger also covers UPDATE OF user_id.
CREATE FUNCTION hms.check_person_user_link() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    taken boolean;
BEGIN
    PERFORM hms.lock_person_user_link(NEW.user_id);
    IF TG_TABLE_NAME = 'staff' THEN
        SELECT EXISTS (SELECT 1 FROM hms.practitioner WHERE user_id = NEW.user_id) INTO taken;
    ELSE
        SELECT EXISTS (SELECT 1 FROM hms.staff WHERE user_id = NEW.user_id) INTO taken;
    END IF;
    IF taken THEN
        RAISE EXCEPTION 'user % is already linked to a doctor or staff record', NEW.user_id
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'uq_person_user_link';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION hms.check_person_user_link() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hms.check_person_user_link() TO hms_app;

CREATE TRIGGER trg_staff_person_user_link BEFORE INSERT OR UPDATE OF user_id ON hms.staff
    FOR EACH ROW EXECUTE FUNCTION hms.check_person_user_link();
CREATE TRIGGER trg_practitioner_person_user_link BEFORE INSERT OR UPDATE OF user_id ON hms.practitioner
    FOR EACH ROW EXECUTE FUNCTION hms.check_person_user_link();

ALTER TABLE hms.staff ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.staff FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.staff
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
