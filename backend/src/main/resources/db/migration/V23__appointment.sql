-- [E5-1] Appointments (APT-1, BR-A1, BR-A2; domain-model.md invariant I4). Design: database-design.md section 20d.
-- Runs as hms_owner. btree_gist is installed by V1 (D5), so no extension is created here.
--
-- An appointment is never deleted (no DELETE grant): it leaves the schedule by status (CANCELLED, NO_SHOW), set by
-- the state machine of [E5-2]. Times are UTC instants, the half-open range [start_at, end_at).
-- reason is free text the patient gives at booking; it is never audited or logged (field name only).

CREATE TABLE hms.appointment (
    id                   uuid        NOT NULL,
    organization_id      uuid        NOT NULL,
    patient_id           uuid        NOT NULL,
    practitioner_id      uuid        NOT NULL,
    start_at             timestamptz NOT NULL,
    end_at               timestamptz NOT NULL,
    type                 text        NOT NULL DEFAULT 'SCHEDULED',
    status               text        NOT NULL DEFAULT 'SCHEDULED',
    reason               text,
    -- true when booked outside the practitioner's availability with appointment:override (BR-A2, audited)
    outside_availability boolean     NOT NULL DEFAULT false,
    created_by           uuid        NOT NULL,
    version              bigint      NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    CONSTRAINT pk_appointment PRIMARY KEY (id),
    CONSTRAINT uq_appointment_org_id UNIQUE (organization_id, id),
    CONSTRAINT fk_appointment_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT fk_appointment_practitioner FOREIGN KEY (organization_id, practitioner_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT ck_appointment_range CHECK (end_at > start_at),
    CONSTRAINT ck_appointment_type CHECK (type IN ('SCHEDULED', 'WALK_IN')),
    CONSTRAINT ck_appointment_status CHECK (
        status IN ('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED', 'CANCELLED', 'NO_SHOW')),
    CONSTRAINT ck_appointment_reason CHECK (reason IS NULL OR char_length(reason) BETWEEN 1 AND 500),
    -- I4 / BR-A1: a practitioner has no two overlapping appointments that hold time. CANCELLED and NO_SHOW free it.
    -- The application serializes bookings per practitioner (advisory lock) so concurrent requests queue instead of
    -- deadlocking on this check; the constraint stays the authority.
    CONSTRAINT ex_appointment_practitioner_overlap EXCLUDE USING gist (
        practitioner_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&)
        WHERE (status IN ('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED'))
);
CREATE INDEX ix_appointment_practitioner_start ON hms.appointment (organization_id, practitioner_id, start_at);
CREATE INDEX ix_appointment_patient_start ON hms.appointment (organization_id, patient_id, start_at);

ALTER TABLE hms.appointment ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.appointment FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.appointment
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
