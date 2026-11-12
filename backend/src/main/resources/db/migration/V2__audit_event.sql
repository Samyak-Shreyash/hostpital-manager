-- [E1-1] Audit core: append-only, monthly-partitioned audit_event (ADR-0007, PRD AUD-1/2/4, invariant I8).
-- Design: docs/architecture/database-design.md section 11. Runs as hms_owner.
-- FK to organization is added by the organization migration ([E1-6]); actor FK by identity ([E1-3]).

CREATE TABLE hms.audit_event (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    occurred_at     timestamptz NOT NULL,
    actor_id        uuid,                     -- NULL = system or unauthenticated caller
    action          text        NOT NULL,
    entity_type     text        NOT NULL,
    entity_id       uuid,
    outcome         text        NOT NULL,
    correlation_id  text,
    changed_fields  text[]      NOT NULL DEFAULT '{}',  -- field NAMES only, never values (AUD-4)
    CONSTRAINT pk_audit_event PRIMARY KEY (id, occurred_at),
    CONSTRAINT ck_audit_event_action CHECK (action ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_audit_event_entity_type CHECK (entity_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_audit_event_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
    CONSTRAINT ck_audit_event_correlation CHECK (correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    -- Defence in depth against values in metadata: each entry must look like a camelCase/dotted field name.
    CONSTRAINT ck_audit_event_changed_fields CHECK (
        cardinality(changed_fields) <= 50
        AND array_position(changed_fields, NULL) IS NULL
        AND array_to_string(changed_fields, ',')
            ~ '^$|^[a-z][A-Za-z0-9]{0,63}(\.[a-z][A-Za-z0-9]{0,63})*(,[a-z][A-Za-z0-9]{0,63}(\.[a-z][A-Za-z0-9]{0,63})*)*$')
) PARTITION BY RANGE (occurred_at);

CREATE INDEX ix_audit_event_org_time ON hms.audit_event (organization_id, occurred_at);
CREATE INDEX ix_audit_event_org_entity ON hms.audit_event (organization_id, entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_event_org_actor ON hms.audit_event (organization_id, actor_id, occurred_at);

-- Append-only (section 3): the app may only read and insert. Row triggers are cloned onto every partition.
REVOKE UPDATE ON hms.audit_event FROM hms_app;
CREATE TRIGGER trg_audit_event_immutable BEFORE UPDATE OR DELETE ON hms.audit_event
    FOR EACH ROW EXECUTE FUNCTION hms.reject_modification();
CREATE TRIGGER trg_audit_event_no_truncate BEFORE TRUNCATE ON hms.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION hms.reject_modification();

-- Creates the current month's partition and the next `months_ahead` ones if missing; returns how many it created.
-- SECURITY DEFINER so the scheduled job can run as hms_app without DDL rights: the body only ever creates
-- audit_event partitions for computed UTC month bounds, the argument is bounded, and search_path is pinned.
-- No DEFAULT partition on purpose: a missing month fails audit inserts, and with them the business write (fail-closed).
CREATE FUNCTION hms.ensure_audit_event_partitions(months_ahead integer) RETURNS integer
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
DECLARE
    first_month timestamp := date_trunc('month', now() AT TIME ZONE 'UTC');
    month_start timestamp;
    partition   text;
    created     integer := 0;
BEGIN
    IF months_ahead IS NULL OR months_ahead < 0 OR months_ahead > 12 THEN
        RAISE EXCEPTION 'months_ahead must be between 0 and 12' USING ERRCODE = '22023';
    END IF;
    -- Serialise concurrent callers (several app instances, startup + schedule).
    PERFORM pg_advisory_xact_lock(hashtext('hms.audit_event partitions'));
    FOR i IN 0..months_ahead LOOP
        month_start := first_month + make_interval(months => i);
        partition := 'audit_event_' || to_char(month_start, 'YYYY_MM');
        IF to_regclass('hms.' || partition) IS NULL THEN
            EXECUTE format('CREATE TABLE hms.%I PARTITION OF hms.audit_event FOR VALUES FROM (%L) TO (%L)',
                partition,
                (month_start AT TIME ZONE 'UTC')::text,
                ((month_start + interval '1 month') AT TIME ZONE 'UTC')::text);
            -- Access goes through the parent only; TRUNCATE triggers are not inherited, so add one per partition.
            EXECUTE format('REVOKE ALL ON hms.%I FROM hms_app', partition);
            EXECUTE format('CREATE TRIGGER %I BEFORE TRUNCATE ON hms.%I FOR EACH STATEMENT '
                || 'EXECUTE FUNCTION hms.reject_modification()', 'trg_' || partition || '_no_truncate', partition);
            created := created + 1;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;
REVOKE ALL ON FUNCTION hms.ensure_audit_event_partitions(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION hms.ensure_audit_event_partitions(integer) TO hms_app;

SELECT hms.ensure_audit_event_partitions(3);
