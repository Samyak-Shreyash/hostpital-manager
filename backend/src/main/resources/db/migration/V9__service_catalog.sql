-- [E2-3] Service catalog with versioned prices (billing module). Design: docs/architecture/database-design.md section 18.
-- Runs as hms_owner. Needs btree_gist (installed by V1) for the exclusion constraint on price ranges.
-- No DELETE grant on either table: a service is retired with active=false, a price is superseded, never removed.

CREATE TABLE hms.service_catalog_item (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    active          boolean     NOT NULL DEFAULT true,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_service_catalog_item PRIMARY KEY (id),
    CONSTRAINT fk_service_catalog_item_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id),
    CONSTRAINT uq_service_catalog_item_org_id UNIQUE (organization_id, id),
    CONSTRAINT uq_service_catalog_item_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_service_catalog_item_code CHECK (code ~ '^[A-Z0-9_]{2,30}$'),
    CONSTRAINT ck_service_catalog_item_name CHECK (char_length(name) BETWEEN 1 AND 150)
);

-- One row per price version. Validity is the half-open range [effective_from, effective_to): the start instant
-- belongs to the version, the end instant belongs to the next one. effective_to IS NULL means open-ended
-- (the current/latest version). Ranges of one service may not overlap (EXCLUDE), also under concurrent inserts.
CREATE TABLE hms.service_price (
    id              uuid          NOT NULL,
    organization_id uuid          NOT NULL,
    service_id      uuid          NOT NULL,
    amount          numeric(14,2) NOT NULL,
    effective_from  timestamptz   NOT NULL,
    effective_to    timestamptz,
    created_by      uuid          NOT NULL,
    created_at      timestamptz   NOT NULL,
    CONSTRAINT pk_service_price PRIMARY KEY (id),
    CONSTRAINT fk_service_price_service FOREIGN KEY (organization_id, service_id)
        REFERENCES hms.service_catalog_item (organization_id, id),
    CONSTRAINT ck_service_price_amount CHECK (amount >= 0),
    CONSTRAINT ck_service_price_range CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT ex_service_price_no_overlap EXCLUDE USING gist (
        service_id WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&)
);
CREATE INDEX ix_service_price_service_from ON hms.service_price (organization_id, service_id, effective_from DESC);

-- A price is immutable once written. The only permitted change is closing the open-ended latest version
-- (effective_to NULL -> a later instant) when its successor is added. hms_app gets UPDATE on that column only.
CREATE FUNCTION hms.guard_service_price() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'immutable row: DELETE on %.% is not allowed', TG_TABLE_SCHEMA, TG_TABLE_NAME
            USING ERRCODE = 'HM001';
    END IF;
    IF NEW.id IS DISTINCT FROM OLD.id
       OR NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.service_id IS DISTINCT FROM OLD.service_id
       OR NEW.amount IS DISTINCT FROM OLD.amount
       OR NEW.effective_from IS DISTINCT FROM OLD.effective_from
       OR NEW.created_by IS DISTINCT FROM OLD.created_by
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
       OR OLD.effective_to IS NOT NULL
       OR NEW.effective_to IS NULL THEN
        RAISE EXCEPTION 'immutable row: only closing an open price range is allowed on %.%',
            TG_TABLE_SCHEMA, TG_TABLE_NAME USING ERRCODE = 'HM001';
    END IF;
    RETURN NEW;
END;
$$;
REVOKE ALL ON FUNCTION hms.guard_service_price() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hms.guard_service_price() TO hms_app;

CREATE TRIGGER trg_service_price_guard BEFORE UPDATE OR DELETE ON hms.service_price
    FOR EACH ROW EXECUTE FUNCTION hms.guard_service_price();

REVOKE UPDATE ON hms.service_price FROM hms_app;
GRANT UPDATE (effective_to) ON hms.service_price TO hms_app;

ALTER TABLE hms.service_catalog_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.service_catalog_item FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.service_catalog_item
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.service_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.service_price FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.service_price
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
