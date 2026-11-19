-- [E5-3] Check-in, walk-in and queue tokens (APT-3, domain-model.md section 6). Design: database-design.md section 20f.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE (no DELETE) from the default privileges of V1 (ADR-0015).
--
-- A token is a per-doctor, per-day sequence number issued at check-in. token_date is the check-in instant's date in the
-- organization's time zone. The counter row is locked by the upsert until the check-in commits, so concurrent check-ins
-- of one doctor and day queue up and get 1..n; a rollback gives the number back.

CREATE TABLE hms.queue_token_counter (
    organization_id uuid    NOT NULL,
    doctor_id       uuid    NOT NULL,   -- the practitioner id
    token_date      date    NOT NULL,
    next_value      integer NOT NULL DEFAULT 1,
    CONSTRAINT pk_queue_token_counter PRIMARY KEY (organization_id, doctor_id, token_date),
    CONSTRAINT fk_queue_token_counter_practitioner FOREIGN KEY (organization_id, doctor_id)
        REFERENCES hms.practitioner (organization_id, id),
    CONSTRAINT ck_queue_token_counter_next CHECK (next_value >= 1)
);
ALTER TABLE hms.queue_token_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.queue_token_counter FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.queue_token_counter
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.appointment
    ADD COLUMN token_no   integer,
    ADD COLUMN token_date date,
    ADD CONSTRAINT ck_appointment_token CHECK (
        (token_no IS NULL AND token_date IS NULL) OR (token_no >= 1 AND token_date IS NOT NULL));

-- One token number per doctor and day (the appointment's doctor column is practitioner_id); the same index serves the live queue.
CREATE UNIQUE INDEX uq_appointment_token ON hms.appointment (organization_id, practitioner_id, token_date, token_no)
    WHERE token_no IS NOT NULL;
-- Walk-ins do not hold a slot: only SCHEDULED-type appointments take part in the no-overlap rule (I4 / BR-A1).
ALTER TABLE hms.appointment DROP CONSTRAINT ex_appointment_practitioner_overlap;
ALTER TABLE hms.appointment ADD CONSTRAINT ex_appointment_practitioner_overlap EXCLUDE USING gist (
    practitioner_id WITH =,
    tstzrange(start_at, end_at, '[)') WITH &&)
    WHERE (type = 'SCHEDULED' AND status IN ('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED'));
