-- [E8-6] IPD invoices, interim and final (ruling R4). Design: database-design.md section 20o.
-- Runs as hms_owner. No new table, no new grant: hms_app already has SELECT/INSERT/UPDATE on invoice and
-- invoice_item. The invoice guard of V31 freezes every column but status/version/updated_at after issue (to_jsonb),
-- so the new columns are covered without changing it.
--
-- * invoice.kind: INTERIM or FINAL for an IPD invoice, NULL for OPD (existing rows are OPD and stay NULL).
-- * invoice.room_charges_through: the last room-charged date (org time zone) of an IPD invoice; a new IPD draft
--   charges only dates after the latest value among the admission's live (not DISCARDED/CANCELLED) IPD invoices.
-- * invoice_item.kind: ROOM marks a room-charge line generated from the admission's bed-days; those lines cannot be
--   changed or dropped by an items replace. STANDARD is every other line (all existing rows).

ALTER TABLE hms.invoice ADD COLUMN kind text;
ALTER TABLE hms.invoice ADD COLUMN room_charges_through date;
ALTER TABLE hms.invoice ADD CONSTRAINT ck_invoice_kind CHECK (kind IN ('INTERIM', 'FINAL'));
ALTER TABLE hms.invoice ADD CONSTRAINT ck_invoice_kind_ipd CHECK ((type = 'IPD') = (kind IS NOT NULL));
ALTER TABLE hms.invoice ADD CONSTRAINT ck_invoice_room_through_ipd CHECK (
    room_charges_through IS NULL OR type = 'IPD');

-- At most one live FINAL invoice per admission.
CREATE UNIQUE INDEX uq_invoice_admission_final_live ON hms.invoice (organization_id, admission_id)
    WHERE kind = 'FINAL' AND status NOT IN ('DISCARDED', 'CANCELLED');
CREATE INDEX ix_invoice_admission ON hms.invoice (organization_id, admission_id) WHERE admission_id IS NOT NULL;

ALTER TABLE hms.invoice_item ADD COLUMN kind text NOT NULL DEFAULT 'STANDARD';
ALTER TABLE hms.invoice_item ADD CONSTRAINT ck_invoice_item_kind CHECK (kind IN ('STANDARD', 'ROOM'));
