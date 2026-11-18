#!/bin/sh
# [E0-6] Database roles (ADR-0015, docs/architecture/database-design.md section 3).
# Run once by the postgres image on an empty data volume, as the admin superuser (POSTGRES_USER).
# hms_owner owns the database and runs Flyway; hms_app is the runtime login with DML only.
# Staging/prod run the same SQL once by hand. Test copy: backend/src/integrationTest/resources/db/init-roles.sql.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v owner_pw="$HMS_DB_MIGRATION_PASSWORD" -v app_pw="$HMS_DB_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE hms_owner LOGIN PASSWORD :'owner_pw';
CREATE ROLE hms_app LOGIN PASSWORD :'app_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
ALTER DATABASE :"db" OWNER TO hms_owner;
-- pg_trgm before Flyway, so name search can use its trigram index under row-level security (ADR-0010):
-- the operator function must be LEAKPROOF, which only a superuser can set. V1 then skips the extension.
CREATE SCHEMA hms AUTHORIZATION hms_owner;
CREATE EXTENSION pg_trgm WITH SCHEMA hms;
ALTER FUNCTION hms.word_similarity_commutator_op(text, text) LEAKPROOF;
SQL
