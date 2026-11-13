-- [E1-7] New permission role:assign: lets an administrator assign built-in default roles without holding their
-- permissions (api-design section 3). Granted to the default SYSTEM_ADMIN role (template role:*). Idempotent.
-- role_permission is under forced RLS, so the organization is set (transaction-local) per organization, looping
-- over hms.organization (not under RLS), as V11 does.
INSERT INTO hms.permission (code) VALUES ('role:assign') ON CONFLICT DO NOTHING;

DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, id, 'role:assign' FROM hms.role
        WHERE organization_id = org AND code = 'SYSTEM_ADMIN'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
