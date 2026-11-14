-- Test-only role provisioning (ADR-0015), mirrors infra/docker/db-init/01-roles.sh.
-- Runs as the Testcontainers superuser against the `test` database. Passwords are throwaway test values.
CREATE ROLE hms_owner LOGIN PASSWORD 'owner-test-only';
CREATE ROLE hms_app LOGIN PASSWORD 'app-test-only' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER DATABASE test OWNER TO hms_owner;
-- Superuser step (ADR-0010): pg_trgm is installed here, before Flyway, so its name-search operator function can be
-- marked LEAKPROOF. Otherwise row-level security stops the trigram index from being used. V1 then skips the extension.
CREATE SCHEMA hms AUTHORIZATION hms_owner;
CREATE EXTENSION pg_trgm WITH SCHEMA hms;
ALTER FUNCTION hms.word_similarity_commutator_op(text, text) LEAKPROOF;
