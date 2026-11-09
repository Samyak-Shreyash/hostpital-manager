-- Test-only role provisioning (ADR-0015), mirrors infra/docker/db-init/01-roles.sh.
-- Runs as the Testcontainers superuser against the `test` database. Passwords are throwaway test values.
CREATE ROLE hms_owner LOGIN PASSWORD 'owner-test-only';
CREATE ROLE hms_app LOGIN PASSWORD 'app-test-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER DATABASE test OWNER TO hms_owner;
