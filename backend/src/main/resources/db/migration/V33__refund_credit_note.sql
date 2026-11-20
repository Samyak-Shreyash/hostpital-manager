-- [E7-4] Refunds with approval and credit notes (ADR-0012). Design: database-design.md section 20n.
-- Runs as hms_owner (ADR-0015). All three tables are append-only: hms_app keeps SELECT/INSERT only, and
-- hms.reject_modification() triggers refuse UPDATE, DELETE and TRUNCATE.
--
-- A refund's status is derived, never updated: initial_status is RECORDED (amount <= the approval threshold) or
-- PENDING_APPROVAL; a pending refund gets at most one refund_decision row (APPROVED or REJECTED). Effective status:
-- RECORDED if initial_status = RECORDED or the decision is APPROVED; REJECTED if rejected; else PENDING_APPROVAL.
-- Refund numbers come from hms.document_sequence ('REFUND', FYyyyy-yy), credit notes from ('CREDIT_NOTE', ...).

ALTER TABLE hms.document_sequence DROP CONSTRAINT ck_document_sequence_type;
ALTER TABLE hms.document_sequence ADD CONSTRAINT ck_document_sequence_type
    CHECK (document_type IN ('UHID', 'ADMISSION', 'INVOICE', 'CREDIT_NOTE', 'RECEIPT', 'REFUND'));

CREATE TABLE hms.refund (
    id              uuid          NOT NULL,
    organization_id uuid          NOT NULL,
    payment_id      uuid          NOT NULL,
    amount          numeric(14,2) NOT NULL,
    mode            text          NOT NULL,
    reason          text          NOT NULL,
    refund_no       text          NOT NULL,
    initial_status  text          NOT NULL,
    requested_at    timestamptz   NOT NULL,
    requested_by    uuid          NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT pk_refund PRIMARY KEY (id),
    CONSTRAINT uq_refund_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_refund_org_no UNIQUE (organization_id, refund_no),
    CONSTRAINT fk_refund_payment FOREIGN KEY (organization_id, payment_id)
        REFERENCES hms.payment (organization_id, id),
    CONSTRAINT fk_refund_requested_by FOREIGN KEY (organization_id, requested_by)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT ck_refund_amount CHECK (amount > 0),
    CONSTRAINT ck_refund_mode CHECK (mode IN ('CASH', 'CARD', 'UPI', 'BANK_TRANSFER', 'CHEQUE')),
    CONSTRAINT ck_refund_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_refund_no CHECK (refund_no ~ '^RF-[0-9]{4}-[0-9]{2}/[0-9]{6}$'),
    CONSTRAINT ck_refund_initial_status CHECK (initial_status IN ('RECORDED', 'PENDING_APPROVAL'))
);
CREATE INDEX ix_refund_org_payment ON hms.refund (organization_id, payment_id);

CREATE TABLE hms.refund_decision (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    refund_id       uuid        NOT NULL,
    decision        text        NOT NULL,
    reason          text,
    decided_at      timestamptz NOT NULL,
    decided_by      uuid        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_refund_decision PRIMARY KEY (id),
    CONSTRAINT uq_refund_decision_refund UNIQUE (organization_id, refund_id), -- at most one decision per refund
    CONSTRAINT fk_refund_decision_refund FOREIGN KEY (organization_id, refund_id)
        REFERENCES hms.refund (organization_id, id),
    CONSTRAINT fk_refund_decision_decided_by FOREIGN KEY (organization_id, decided_by)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT ck_refund_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT ck_refund_decision_reason CHECK (
        (decision = 'APPROVED' AND reason IS NULL)
        OR (decision = 'REJECTED' AND char_length(btrim(reason)) BETWEEN 1 AND 500))
);

CREATE TABLE hms.credit_note (
    id              uuid          NOT NULL,
    organization_id uuid          NOT NULL,
    invoice_id      uuid          NOT NULL,
    amount          numeric(14,2) NOT NULL,
    reason          text          NOT NULL,
    credit_note_no  text          NOT NULL,
    issued_at       timestamptz   NOT NULL,
    issued_by       uuid          NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT pk_credit_note PRIMARY KEY (id),
    CONSTRAINT uq_credit_note_org_no UNIQUE (organization_id, credit_note_no),
    CONSTRAINT fk_credit_note_invoice FOREIGN KEY (organization_id, invoice_id)
        REFERENCES hms.invoice (organization_id, id),
    CONSTRAINT fk_credit_note_issued_by FOREIGN KEY (organization_id, issued_by)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT ck_credit_note_amount CHECK (amount > 0),
    CONSTRAINT ck_credit_note_reason CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
    CONSTRAINT ck_credit_note_no CHECK (credit_note_no ~ '^CN-[0-9]{4}-[0-9]{2}/[0-9]{6}$')
);
CREATE INDEX ix_credit_note_org_invoice ON hms.credit_note (organization_id, invoice_id);

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['refund', 'refund_decision', 'credit_note'] LOOP
        EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON hms.%I FROM hms_app', t);
        EXECUTE format('GRANT SELECT, INSERT ON hms.%I TO hms_app', t);
        EXECUTE format('CREATE TRIGGER trg_%s_immutable BEFORE UPDATE OR DELETE ON hms.%I'
            || ' FOR EACH ROW EXECUTE FUNCTION hms.reject_modification()', t, t);
        EXECUTE format('CREATE TRIGGER trg_%s_no_truncate BEFORE TRUNCATE ON hms.%I'
            || ' FOR EACH STATEMENT EXECUTE FUNCTION hms.reject_modification()', t, t);
        EXECUTE format('ALTER TABLE hms.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE hms.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY org_isolation ON hms.%I'
            || ' USING (organization_id = NULLIF(current_setting(''app.organization_id'', true), '''')::uuid)'
            || ' WITH CHECK (organization_id = NULLIF(current_setting(''app.organization_id'', true), '''')::uuid)',
            t);
    END LOOP;
END
$$;
