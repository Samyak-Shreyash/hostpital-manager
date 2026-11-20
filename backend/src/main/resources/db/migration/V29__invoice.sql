-- [E7-1] Invoice drafts and line calculations (BIL-1..4, ADR-0012). Design: database-design.md section 21.
-- Runs as hms_owner; hms_app gets SELECT/INSERT/UPDATE from the default privileges of V1 (ADR-0015), plus DELETE on
-- invoice_item only (a DRAFT's items are replaced by delete + insert; the trigger allows that only while DRAFT).
--
-- Money is NUMERIC(14,2) and never negative. Every line stores its rounded amounts (HALF_UP per line); the invoice
-- totals are the sums of the rounded lines, recomputed by the application on every item change. Items snapshot the
-- unit price: a later catalog price never changes a draft. Invoice numbers are assigned at issue ([E7-3]).

CREATE TABLE hms.invoice (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    type             text          NOT NULL,
    patient_id       uuid          NOT NULL,
    encounter_id     uuid,
    admission_id     uuid,
    status           text          NOT NULL DEFAULT 'DRAFT',
    invoice_no       text,
    issued_at        timestamptz,
    issued_by        uuid,
    subtotal         numeric(14,2) NOT NULL DEFAULT 0,
    discount_total   numeric(14,2) NOT NULL DEFAULT 0,
    tax_total        numeric(14,2) NOT NULL DEFAULT 0,
    total            numeric(14,2) NOT NULL DEFAULT 0,
    version          bigint        NOT NULL DEFAULT 0,
    created_by       uuid          NOT NULL,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    CONSTRAINT pk_invoice PRIMARY KEY (id),
    CONSTRAINT uq_invoice_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_invoice_org_no UNIQUE (organization_id, invoice_no),
    CONSTRAINT fk_invoice_patient FOREIGN KEY (organization_id, patient_id)
        REFERENCES hms.patient (organization_id, id),
    CONSTRAINT fk_invoice_encounter FOREIGN KEY (organization_id, encounter_id)
        REFERENCES hms.encounter (organization_id, id),
    -- admission_id gets its composite FK when the admission table exists ([E9-x]).
    CONSTRAINT ck_invoice_type CHECK (type IN ('OPD', 'IPD')),
    CONSTRAINT ck_invoice_source CHECK (
        (type = 'OPD' AND encounter_id IS NOT NULL AND admission_id IS NULL)
        OR (type = 'IPD' AND admission_id IS NOT NULL AND encounter_id IS NULL)),
    CONSTRAINT ck_invoice_status CHECK (
        status IN ('DRAFT', 'ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED', 'DISCARDED')),
    CONSTRAINT ck_invoice_issued CHECK (
        (status IN ('DRAFT', 'DISCARDED') AND invoice_no IS NULL AND issued_at IS NULL AND issued_by IS NULL)
        OR (status NOT IN ('DRAFT', 'DISCARDED') AND invoice_no IS NOT NULL AND issued_at IS NOT NULL
            AND issued_by IS NOT NULL)),
    CONSTRAINT ck_invoice_money CHECK (subtotal >= 0 AND discount_total >= 0 AND tax_total >= 0 AND total >= 0)
);
-- One live (not discarded, not cancelled) invoice per encounter: a second draft would double-bill the visit.
CREATE UNIQUE INDEX uq_invoice_encounter_live ON hms.invoice (organization_id, encounter_id)
    WHERE encounter_id IS NOT NULL AND status NOT IN ('DISCARDED', 'CANCELLED');
CREATE INDEX ix_invoice_patient ON hms.invoice (organization_id, patient_id, created_at);
CREATE INDEX ix_invoice_status ON hms.invoice (organization_id, status, created_at);

CREATE TABLE hms.invoice_item (
    id               uuid          NOT NULL,
    organization_id  uuid          NOT NULL,
    invoice_id       uuid          NOT NULL,
    position         integer       NOT NULL,
    service_id       uuid,
    description      text          NOT NULL,
    quantity         numeric(10,2) NOT NULL,
    unit_price       numeric(14,2) NOT NULL,
    discount         numeric(14,2) NOT NULL DEFAULT 0,
    discount_reason  text,
    tax_rate         numeric(5,2)  NOT NULL DEFAULT 0,
    gross            numeric(14,2) NOT NULL,
    taxable          numeric(14,2) NOT NULL,
    tax              numeric(14,2) NOT NULL,
    total            numeric(14,2) NOT NULL,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT pk_invoice_item PRIMARY KEY (id),
    CONSTRAINT fk_invoice_item_invoice FOREIGN KEY (organization_id, invoice_id)
        REFERENCES hms.invoice (organization_id, id),
    CONSTRAINT fk_invoice_item_service FOREIGN KEY (organization_id, service_id)
        REFERENCES hms.service_catalog_item (organization_id, id),
    CONSTRAINT uq_invoice_item_position UNIQUE (invoice_id, position),
    CONSTRAINT ck_invoice_item_description CHECK (char_length(description) BETWEEN 1 AND 200),
    CONSTRAINT ck_invoice_item_quantity CHECK (quantity BETWEEN 0.01 AND 9999),
    CONSTRAINT ck_invoice_item_tax_rate CHECK (tax_rate BETWEEN 0 AND 100),
    CONSTRAINT ck_invoice_item_money CHECK (unit_price >= 0 AND discount >= 0 AND gross >= 0 AND taxable >= 0
        AND tax >= 0 AND total >= 0),
    CONSTRAINT ck_invoice_item_discount CHECK (discount <= gross),
    CONSTRAINT ck_invoice_item_arithmetic CHECK (taxable = gross - discount AND total = taxable + tax),
    CONSTRAINT ck_invoice_item_discount_reason CHECK (
        discount_reason IS NULL OR char_length(discount_reason) BETWEEN 1 AND 200)
);
CREATE INDEX ix_invoice_item_invoice ON hms.invoice_item (organization_id, invoice_id, position);

GRANT DELETE ON hms.invoice_item TO hms_app;

-- SQLSTATE HM004 (HM001 encounter, HM002 specialty, HM003 prescription): mapped to 409 INVOICE_IMMUTABLE.
--   * invoice: never deleted. DRAFT -> DRAFT / DISCARDED / ISSUED. After issue the business columns are frozen;
--     only the payment status may move (ISSUED <-> PARTIALLY_PAID <-> PAID) or the invoice be CANCELLED (credit
--     note, later task). DISCARDED and CANCELLED are final.
--   * invoice_item: INSERT/UPDATE/DELETE only while the parent is DRAFT.
CREATE FUNCTION hms.guard_invoice() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'an invoice cannot be deleted' USING ERRCODE = 'HM004';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'DISCARDED', 'ISSUED') THEN
        RETURN NEW;
    END IF;
    IF OLD.status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID')
       AND NEW.status IN ('ISSUED', 'PARTIALLY_PAID', 'PAID', 'CANCELLED')
       AND (NEW.id, NEW.organization_id, NEW.type, NEW.patient_id, NEW.encounter_id, NEW.admission_id,
            NEW.invoice_no, NEW.issued_at, NEW.issued_by, NEW.subtotal, NEW.discount_total, NEW.tax_total,
            NEW.total, NEW.created_by, NEW.created_at)
           IS NOT DISTINCT FROM
           (OLD.id, OLD.organization_id, OLD.type, OLD.patient_id, OLD.encounter_id, OLD.admission_id,
            OLD.invoice_no, OLD.issued_at, OLD.issued_by, OLD.subtotal, OLD.discount_total, OLD.tax_total,
            OLD.total, OLD.created_by, OLD.created_at) THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'the invoice is immutable in its current status' USING ERRCODE = 'HM004';
END
$$;

CREATE FUNCTION hms.guard_invoice_item() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    parent_status text;
BEGIN
    IF TG_OP = 'DELETE' THEN
        SELECT status INTO parent_status FROM hms.invoice
        WHERE organization_id = OLD.organization_id AND id = OLD.invoice_id;
    ELSE
        SELECT status INTO parent_status FROM hms.invoice
        WHERE organization_id = NEW.organization_id AND id = NEW.invoice_id;
    END IF;
    IF parent_status IS NOT NULL AND parent_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'the items of a non-draft invoice are immutable' USING ERRCODE = 'HM004';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_invoice_guard BEFORE UPDATE OR DELETE ON hms.invoice
    FOR EACH ROW EXECUTE FUNCTION hms.guard_invoice();
CREATE TRIGGER tg_invoice_item_guard BEFORE INSERT OR UPDATE OR DELETE ON hms.invoice_item
    FOR EACH ROW EXECUTE FUNCTION hms.guard_invoice_item();

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['invoice', 'invoice_item'] LOOP
        EXECUTE format('ALTER TABLE hms.%I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE hms.%I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($p$CREATE POLICY org_isolation ON hms.%I
            USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
            WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)$p$, t);
    END LOOP;
END
$$;
