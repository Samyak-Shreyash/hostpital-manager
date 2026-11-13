-- [E1-6] Organization table, FKs from every org-owned table, and row-level security (ADR-0010, spike outcome [E1-2]).
-- Design: docs/architecture/database-design.md sections 4 and 14. Runs as hms_owner.

CREATE TABLE hms.organization (
    id         uuid        NOT NULL,
    name       text        NOT NULL,
    status     text        NOT NULL DEFAULT 'ACTIVE',
    version    bigint      NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_organization PRIMARY KEY (id),
    CONSTRAINT ck_organization_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_organization_name CHECK (char_length(name) BETWEEN 1 AND 200)
);

-- Backfill so the FKs below can be added on a database that already holds rows (dev/test environments).
-- Runs before RLS is enabled: FORCE ROW LEVEL SECURITY would otherwise hide the role rows from hms_owner.
INSERT INTO hms.organization (id, name, status, created_at, updated_at)
SELECT DISTINCT organization_id, 'Backfilled organization', 'ACTIVE', now(), now()
FROM (
    SELECT organization_id FROM hms.document_sequence
    UNION SELECT organization_id FROM hms.app_user
    UNION SELECT organization_id FROM hms.role
    UNION SELECT organization_id FROM hms.role_permission
    UNION SELECT organization_id FROM hms.user_role
    UNION SELECT organization_id FROM hms.audit_event
) existing
ON CONFLICT (id) DO NOTHING;

ALTER TABLE hms.document_sequence
    ADD CONSTRAINT fk_document_sequence_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);
ALTER TABLE hms.app_user
    ADD CONSTRAINT fk_app_user_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);
ALTER TABLE hms.role
    ADD CONSTRAINT fk_role_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);
ALTER TABLE hms.role_permission
    ADD CONSTRAINT fk_role_permission_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);
ALTER TABLE hms.user_role
    ADD CONSTRAINT fk_user_role_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);
ALTER TABLE hms.audit_event
    ADD CONSTRAINT fk_audit_event_organization FOREIGN KEY (organization_id) REFERENCES hms.organization (id);

-- Row-level security on org-owned tables (section 14). Excluded by ruling: organization, app_user (looked up by
-- email before any org is known), audit_event (written by unauthenticated login flows) and the spring_session
-- tables; app-level org scoping still applies to those. permission is global reference data.
-- NULLIF: a custom setting reads back as '' once used on a connection; an unset context must hide rows, not error.
ALTER TABLE hms.document_sequence ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.document_sequence FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.document_sequence
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.role ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.role FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.role
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.role_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.role_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.role_permission
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

ALTER TABLE hms.user_role ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.user_role FORCE ROW LEVEL SECURITY;
CREATE POLICY org_isolation ON hms.user_role
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
