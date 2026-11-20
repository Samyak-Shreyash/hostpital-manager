-- [E7-8] Permission invoice:discount: a line discount or an invoice-level percent discount needs it. It is granted to
-- every default role template that holds invoice:update (only ACCOUNTANT, invoice:*; new organizations get it from
-- DefaultRoleTemplates). Existing organizations' built-in ACCOUNTANT roles are backfilled; custom roles are not
-- touched. role_permission is under forced RLS, so the organization is set (transaction-local) per organization,
-- looping over hms.organization (not under RLS), as V11 and V26 do. Idempotent.
INSERT INTO hms.permission (code) VALUES ('invoice:discount') ON CONFLICT DO NOTHING;

DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);
        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, id, 'invoice:discount' FROM hms.role
        WHERE organization_id = org AND code = 'ACCOUNTANT'
        ON CONFLICT DO NOTHING;
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
