-- [E1-2] Test-only fixture for RowLevelSecurityFeasibilityIT; loaded only by that test via spring.flyway.locations.
-- Shape mirrors what [E1-6] will apply to real business tables (ADR-0010). Runs as hms_owner.
CREATE TABLE hms.rls_spike (
    id              uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    label           text
);
GRANT SELECT, INSERT, UPDATE ON hms.rls_spike TO hms_app;
ALTER TABLE hms.rls_spike ENABLE ROW LEVEL SECURITY;
ALTER TABLE hms.rls_spike FORCE ROW LEVEL SECURITY;

-- NULLIF: once a custom GUC has been set on a connection it reads back as '' (not NULL) after commit,
-- and ''::uuid would raise instead of hiding rows. An unset context must mean "no rows", not an error.
CREATE POLICY org_isolation ON hms.rls_spike
    USING (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- FORCE applies to the owner too, so seed each org under its own context (transaction-local).
SELECT set_config('app.organization_id', '0190a000-0000-7000-8000-00000000000a', true);
INSERT INTO hms.rls_spike VALUES ('0190a000-0000-7000-8000-0000000000a1', '0190a000-0000-7000-8000-00000000000a', 'seed');
SELECT set_config('app.organization_id', '0190b000-0000-7000-8000-00000000000b', true);
INSERT INTO hms.rls_spike VALUES ('0190b000-0000-7000-8000-0000000000b1', '0190b000-0000-7000-8000-00000000000b', 'seed');
SELECT set_config('app.organization_id', '', true);
