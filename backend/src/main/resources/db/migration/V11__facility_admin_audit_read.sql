-- [E1-9] The default Facility Administrator holds audit:read (api-design section 14, PRD). Backfills existing
-- organizations; new ones get it from DefaultRoleTemplates. Idempotent. role_permission is under forced RLS, so the
-- organization is set (transaction-local) per organization, looping over hms.organization (not under RLS).
DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, id, 'audit:read' FROM hms.role
        WHERE organization_id = org AND code = 'FACILITY_ADMIN'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
