-- [E4-3] Weekly availability windows and leave exceptions (PRC-4). Design: database-design.md section 20a.
-- Runs as hms_owner. btree_gist is installed by V1 (D5), so no extension is created here.
--
-- practitioner_availability is the weekly template, replaced as a set by PUT /doctors/{id}/availability. It is
-- configuration without clinical or financial content and holds no history (bookings carry their own times), so
-- hms_app may DELETE it, as V18 allows for the specialty/department mappings. Each replacement bumps the
-- practitioner's version and is audited. Times are local wall-clock times in the organization's timezone.
--
-- availability_exception (leave) is never deleted: cancelling sets status = CANCELLED (no DELETE grant).

CREATE TABLE hms.practitioner_availability (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    practitioner_id uuid        NOT NULL,
    weekday         smallint    NOT NULL, -- ISO-8601: 1 = Monday ... 7 = Sunday
    start_time      time        NOT NULL,
    end_time        time        NOT NULL,
    slot_minutes    integer     NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_practitioner_availability PRIMARY KEY (id),
    CONSTRAINT fk_practitioner_availability_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT ck_practitioner_availability_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT ck_practitioner_availability_times CHECK (end_time > start_time),
    CONSTRAINT ck_practitioner_availability_slot CHECK (slot_minutes BETWEEN 5 AND 120),
    -- No two windows of one practitioner on the same weekday overlap (half-open, adjacent windows allowed).
    -- time has no range type, so the times are anchored to a fixed date to form an (immutable) tsrange.
    CONSTRAINT ex_practitioner_availability_overlap EXCLUDE USING gist (
        practitioner_id WITH =,
        weekday WITH =,
        tsrange(DATE '2000-01-03' + start_time, DATE '2000-01-03' + end_time, '[)') WITH &&)
);

CREATE TABLE hms.availability_exception (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    practitioner_id uuid        NOT NULL,
    leave_date      date        NOT NULL,
    start_time      time,        -- both NULL = the whole day; otherwise the block [start_time, end_time)
    end_time        time,
    status          text        NOT NULL DEFAULT 'ACTIVE',
    created_by      uuid        NOT NULL,
    cancelled_by    uuid,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_availability_exception PRIMARY KEY (id),
    CONSTRAINT fk_availability_exception_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT ck_availability_exception_times CHECK (
        (start_time IS NULL AND end_time IS NULL)
        OR (start_time IS NOT NULL AND end_time IS NOT NULL AND end_time > start_time)),
    CONSTRAINT ck_availability_exception_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT ck_availability_exception_cancelled CHECK ((status = 'CANCELLED') = (cancelled_by IS NOT NULL))
);
CREATE INDEX ix_availability_exception_practitioner_date
    ON hms.availability_exception (organization_id, practitioner_id, leave_date);

GRANT DELETE ON hms.practitioner_availability TO hms_app;

ALTER TABLE hms.practitioner_availability ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.practitioner_availability FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.practitioner_availability
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.availability_exception ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.availability_exception FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.availability_exception
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
