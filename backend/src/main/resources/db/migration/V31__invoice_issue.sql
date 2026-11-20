-- [E7-2] Issue invoices: gapless numbers, freeze, guard triggers; shared Idempotency-Key store (plan ruling R5).
-- Design: database-design.md section 20l. Runs as hms_owner (ADR-0015).
--
-- Invoice numbers come from hms.document_sequence (V1; 'INVOICE', 'RECEIPT', 'CREDIT_NOTE' are already in its
-- CHECK), allocated last in the issuing transaction by DocumentNumberAllocator with period key FYyyyy-yy.

-- 1. Idempotency records: one per (organization, endpoint, key), append-only. endpoint is the route template
--    ("POST /api/v1/invoices/{id}/issue"); request_hash covers the path ids and body, so the same key sent for
--    another resource or another body is refused (422 IDEMPOTENCY_KEY_REUSED). Only successful responses are stored;
--    a failed request rolls back with its business transaction and may be retried with the same key.
CREATE TABLE hms.idempotency_record (
    organization_id uuid        NOT NULL,
    key             text        NOT NULL,
    endpoint        text        NOT NULL,
    request_hash    text        NOT NULL,
    response_status integer     NOT NULL,
    response_body   jsonb       NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_idempotency_record PRIMARY KEY (organization_id, endpoint, key),
    CONSTRAINT fk_idempotency_record_org FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT ck_idempotency_record_key CHECK (char_length(key) BETWEEN 1 AND 200),
    CONSTRAINT ck_idempotency_record_status CHECK (response_status BETWEEN 200 AND 299)
);

REVOKE UPDATE, DELETE, TRUNCATE ON hms.idempotency_record FROM hms_app;
CREATE TRIGGER trg_idempotency_record_immutable BEFORE UPDATE OR DELETE ON hms.idempotency_record
    FOR EACH ROW EXECUTE FUNCTION hms.reject_modification();
CREATE TRIGGER trg_idempotency_record_no_truncate BEFORE TRUNCATE ON hms.idempotency_record
    FOR EACH STATEMENT EXECUTE FUNCTION hms.reject_modification();

ALTER TABLE hms.idempotency_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.idempotency_record FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.idempotency_record
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- 2. Invoice guard, replacing V29's function (SQLSTATE HM004 -> 409 INVOICE_IMMUTABLE; HM001 is the encounter's).
--    * never deleted;
--    * a DRAFT may stay DRAFT, be DISCARDED or ISSUED (ck_invoice_issued requires number, issued_at, issued_by);
--    * otherwise (OLD.status <> DRAFT) every column except status, version and updated_at is frozen, and status may
--      only move from ISSUED / PARTIALLY_PAID / PAID to PARTIALLY_PAID, PAID or CANCELLED, or back to ISSUED (a
--      refund of a partial payment, payment port of [E7-1]). DISCARDED and CANCELLED are final.
CREATE OR REPLACE FUNCTION hms.guard_invoice() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an invoice cannot be deleted' USING ERRCODE = 'HM004';
    END IF;
    IF OLD.status = 'DRAFT' THEN
        IF NEW.status IN ('DRAFT', 'DISCARDED', 'ISSUED') THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'a draft invoice can only be discarded or issued' USING ERRCODE = 'HM004';
    END IF;
    IF OLD.status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID')
       AND NEW.status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')
       AND (to_jsonb(NEW) - ARRAY['status', 'version', 'updated_at'])
           = (to_jsonb(OLD) - ARRAY['status', 'version', 'updated_at']) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'the invoice is immutable in its current status' USING ERRCODE = 'HM004';
END
$$;

-- 3. Item guard, replacing V29's function: INSERT/UPDATE/DELETE only while the parent is DRAFT. A missing parent
--    is left to the foreign key.
CREATE OR REPLACE FUNCTION hms.guard_invoice_item() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT status INTO parent_status FROM hms.invoice
        WHERE organization_id = OLD.organization_id AND id = OLD.invoice_id;
        IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'the items of a non-draft invoice are immutable' USING ERRCODE = 'HM004';
        END IF;
    END IF;
    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT status INTO parent_status FROM hms.invoice
        WHERE organization_id = NEW.organization_id AND id = NEW.invoice_id;
        IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
            RAISE EXCEPTION 'the items of a non-draft invoice are immutable' USING ERRCODE = 'HM004';
        END IF;
        RETURN NEW;
    END IF;
    RETURN OLD;
END
$$;
