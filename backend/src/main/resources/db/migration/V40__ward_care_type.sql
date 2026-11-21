-- [E8-6] Ward care type for the room-rent GST rule (owner decision 2026-11-21, review-notes). Design:
-- docs/architecture/database-design.md section 20. CRITICAL_CARE = ICU, CCU, ICCU, NICU (GST-exempt room rent).
ALTER TABLE hms.ward
    ADD COLUMN care_type text NOT NULL DEFAULT 'GENERAL',
    ADD CONSTRAINT ck_ward_care_type CHECK (care_type IN ('GENERAL', 'CRITICAL_CARE'));
