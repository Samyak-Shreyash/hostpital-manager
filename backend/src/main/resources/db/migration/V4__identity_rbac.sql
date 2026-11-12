-- [E1-5] RBAC (ADR-0006): permission catalogue, org-owned roles, role -> permission and user -> role mappings.
-- Design: docs/architecture/database-design.md section 13. Runs as hms_owner.
-- FKs to organization and RLS policies are added by the organization migration ([E1-6]).

-- Global reference data: the code-defined catalogue (com.hms.identity.api.Permissions). Not org-owned.
CREATE TABLE hms.permission (
    code text NOT NULL,
    CONSTRAINT pk_permission PRIMARY KEY (code),
    CONSTRAINT ck_permission_code CHECK (code ~ '^[a-z]+:[a-z-]+$')
);
-- The catalogue changes only by migration.
REVOKE INSERT, UPDATE ON hms.permission FROM hms_app;

INSERT INTO hms.permission (code) VALUES
    ('admission:create'),
    ('admission:read'),
    ('admission:update'),
    ('admission:cancel'),
    ('appointment:create'),
    ('appointment:read'),
    ('appointment:update'),
    ('appointment:cancel'),
    ('appointment:check-in'),
    ('appointment:backdate'),
    ('appointment:override'),
    ('audit:read'),
    ('bed:read'),
    ('bed:manage'),
    ('bed:assign'),
    ('catalog:read'),
    ('catalog:manage'),
    ('discharge:write'),
    ('discharge:complete'),
    ('discharge:defer-billing'),
    ('encounter:create'),
    ('encounter:read'),
    ('encounter:update'),
    ('encounter:update-any'),
    ('encounter:finalize'),
    ('encounter:addendum'),
    ('invoice:create'),
    ('invoice:read'),
    ('invoice:update'),
    ('invoice:issue'),
    ('invoice:credit'),
    ('invoice:price-override'),
    ('observation:write'),
    ('org:read'),
    ('org:update'),
    ('patient:create'),
    ('patient:read'),
    ('patient:search'),
    ('patient:update'),
    ('patient:update-clinical'),
    ('payment:create'),
    ('payment:read'),
    ('practitioner:create'),
    ('practitioner:read'),
    ('practitioner:update'),
    ('practitioner:schedule'),
    ('prescription:create'),
    ('prescription:read'),
    ('prescription:issue'),
    ('refund:create'),
    ('refund:read'),
    ('refund:approve'),
    ('report:clinical'),
    ('report:finance'),
    ('role:read'),
    ('role:manage'),
    ('user:create'),
    ('user:read'),
    ('user:update'),
    ('user:reset-password'),
    ('ward:read'),
    ('ward:manage');

CREATE TABLE hms.role (
    id              uuid        NOT NULL,
    organization_id uuid        NOT NULL,
    code            text        NOT NULL,
    name            text        NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    CONSTRAINT pk_role PRIMARY KEY (id),
    CONSTRAINT uq_role_org_id UNIQUE (organization_id, id), -- target for same-org composite FKs (section 4)
    CONSTRAINT uq_role_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_role_code CHECK (code ~ '^[A-Z][A-Z0-9_]{0,49}$'),
    CONSTRAINT ck_role_name CHECK (char_length(name) BETWEEN 1 AND 100)
);

CREATE TABLE hms.role_permission (
    organization_id uuid NOT NULL,
    role_id         uuid NOT NULL,
    permission_code text NOT NULL,
    CONSTRAINT pk_role_permission PRIMARY KEY (role_id, permission_code),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (organization_id, role_id)
        REFERENCES hms.role (organization_id, id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_code) REFERENCES hms.permission (code)
);
CREATE INDEX ix_role_permission_org_role ON hms.role_permission (organization_id, role_id);

CREATE TABLE hms.user_role (
    organization_id uuid NOT NULL,
    user_id         uuid NOT NULL,
    role_id         uuid NOT NULL,
    CONSTRAINT pk_user_role PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (organization_id, user_id)
        REFERENCES hms.app_user (organization_id, id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (organization_id, role_id)
        REFERENCES hms.role (organization_id, id)
);
CREATE INDEX ix_user_role_org_role ON hms.user_role (organization_id, role_id);
-- Mapping rows have no identity of their own and carry no clinical/financial data; DELETE grants for
-- removing a permission from a role or a role from a user come with role/user management ([E1-7]).
