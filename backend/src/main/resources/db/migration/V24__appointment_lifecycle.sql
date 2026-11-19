-- [E5-2] Appointment state machine: reschedule, cancel, no-show (domain-model.md section 6).
-- Design: database-design.md section 20e. Runs as hms_owner; hms_app already has SELECT/INSERT/UPDATE (no DELETE).
--
-- RESCHEDULED is a status of the old appointment; the new one points back with rescheduled_from_id.
-- cancel_reason is free text and is never audited or logged (field name only). Permissions appointment:cancel and
-- appointment:update exist since V4 and the role templates hold them, so nothing is granted here.
-- The V23 exclusion constraint lists the time-holding statuses positively, so RESCHEDULED already frees the time.

ALTER TABLE hms.appointment
    ADD COLUMN rescheduled_from_id uuid,
    ADD COLUMN cancel_reason       text,
    ADD COLUMN cancelled_at        timestamptz;

ALTER TABLE hms.appointment
    ADD CONSTRAINT fk_appointment_rescheduled_from FOREIGN KEY (organization_id, rescheduled_from_id)
        REFERENCES hms.appointment (organization_id, id),
    ADD CONSTRAINT ck_appointment_cancel_reason CHECK (cancel_reason IS NULL OR char_length(cancel_reason) BETWEEN 1 AND 500),
    ADD CONSTRAINT ck_appointment_not_self_reschedule CHECK (rescheduled_from_id IS NULL OR rescheduled_from_id <> id);

ALTER TABLE hms.appointment DROP CONSTRAINT ck_appointment_status;
ALTER TABLE hms.appointment ADD CONSTRAINT ck_appointment_status CHECK (
    status IN ('SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION', 'COMPLETED', 'CANCELLED', 'NO_SHOW', 'RESCHEDULED'));

-- An appointment is rescheduled at most once: it has at most one successor.
CREATE UNIQUE INDEX uq_appointment_rescheduled_from ON hms.appointment (rescheduled_from_id)
    WHERE rescheduled_from_id IS NOT NULL;
-- Day and status listings of the organization (GET /appointments?date=).
CREATE INDEX ix_appointment_org_start ON hms.appointment (organization_id, start_at);
