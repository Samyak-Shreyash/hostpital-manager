-- Owner decisions 2026-11-14 (docs/00-review-notes.md): role-template changes and staff permissions. Runs as hms_owner.
--  * New permissions staff:read|create|update (staff endpoints, independent of practitioner:*), granted to the
--    built-in FACILITY_ADMIN role.
--  * Built-in DOCTOR and NURSE roles get patient:update-clinical (allergies / medical history).
--  * Built-in RECEPTIONIST roles lose patient:update-clinical and patient:update-identity.
-- Built-in roles are identified by their template code (unique per organization), as V11 does. Custom roles are
-- never touched. Idempotent, per organization under forced RLS, as V12/V15/V16 do.

INSERT INTO hms.permission (code) VALUES ('staff:read'), ('staff:create'), ('staff:update') ON CONFLICT DO NOTHING;

DO $$
DECLARE
    org uuid;
BEGIN
    FOR org IN SELECT id FROM hms.organization LOOP
        PERFORM set_config('app.organization_id', org::text, true);

        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT r.organization_id, r.id, p.code
        FROM hms.role r CROSS JOIN (VALUES ('staff:read'), ('staff:create'), ('staff:update')) AS p (code)
        WHERE r.organization_id = org AND r.code = 'FACILITY_ADMIN'
        ON CONFLICT DO NOTHING;

        INSERT INTO hms.role_permission (organization_id, role_id, permission_code)
        SELECT organization_id, id, 'patient:update-clinical' FROM hms.role
        WHERE organization_id = org AND code IN ('DOCTOR', 'NURSE')
        ON CONFLICT DO NOTHING;

        DELETE FROM hms.role_permission rp
        USING hms.role r
        WHERE rp.organization_id = org AND r.organization_id = org AND rp.role_id = r.id
          AND r.code = 'RECEPTIONIST'
          AND rp.permission_code IN ('patient:update-clinical', 'patient:update-identity');
    END LOOP;
    PERFORM set_config('app.organization_id', '', true);
END
$$;
