-- [E7-3] Payments: partial, idempotent, row-locked (ADR-0012). Design: database-design.md section 20m.
-- Runs as hms_owner (ADR-0015). Append-only: hms_app keeps SELECT/INSERT only, and hms.reject_modification()
-- triggers refuse UPDATE, DELETE and TRUNCATE. Corrections are refunds ([E7-4]), never edits.
--
-- The amount paid on an invoice is derived (SUM of its payments), not stored (ADR-0012). Receipt numbers come from
-- hms.document_sequence ('RECEIPT', period FYyyyy-yy), allocated last in the recording transaction.

CREATE TABLE hms.payment (
    id              uuid          NOT NULL,
    organization_id uuid          NOT NULL,
    invoice_id      uuid          NOT NULL,
    amount          numeric(14,2) NOT NULL,
    mode            text          NOT NULL,
    reference_no    text,
    receipt_no      text          NOT NULL,
    received_at     timestamptz   NOT NULL,
    received_by     uuid          NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uq_payment_org_id UNIQUE (organization_id, id), -- target for refunds' same-org FK
    CONSTRAINT uq_payment_org_receipt UNIQUE (organization_id, receipt_no),
    CONSTRAINT fk_payment_invoice FOREIGN KEY (organization_id, invoice_id)
        REFERENCES hms.invoice (organization_id, id),
    CONSTRAINT fk_payment_received_by FOREIGN KEY (organization_id, received_by)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_mode CHECK (mode IN ('CASH', 'CARD', 'UPI', 'BANK_TRANSFER', 'CHEQUE')),
    CONSTRAINT ck_payment_reference_no CHECK (reference_no IS NULL OR char_length(reference_no) BETWEEN 1 AND 50),
    CONSTRAINT ck_payment_receipt_no CHECK (receipt_no ~ '^RCT-[0-9]{4}-[0-9]{2}/[0-9]{6}$')
);
CREATE INDEX ix_payment_org_invoice ON hms.payment (organization_id, invoice_id);
CREATE INDEX ix_payment_org_received_at ON hms.payment (organization_id, received_at DESC);

REVOKE UPDATE, DELETE, TRUNCATE ON hms.payment FROM hms_app;
CREATE TRIGGER trg_payment_immutable BEFORE UPDATE OR DELETE ON hms.payment
    FOR EACH ROW EXECUTE FUNCTION hms.reject_modification();
CREATE TRIGGER trg_payment_no_truncate BEFORE TRUNCATE ON hms.payment
    FOR EACH STATEMENT EXECUTE FUNCTION hms.reject_modification();

ALTER TABLE hms.payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.payment FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.payment
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
