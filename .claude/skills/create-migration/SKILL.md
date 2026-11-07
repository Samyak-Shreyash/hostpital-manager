---
name: create-migration
description: Create the next Flyway migration for the HMS backend (PostgreSQL) and verify it against real Postgres via Testcontainers.
argument-hint: "<what the migration does, e.g. add bed_assignment with one-open-per-bed constraint>"
disable-model-invocation: true
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
  - Write
  - Edit
---

# create-migration

Task: $ARGUMENTS

Flyway owns the schema and is **forward-only**. A schema change exists only once a migration exists. Design source of truth: `docs/architecture/database-design.md` (schema, constraints, concurrency, migration order) and `docs/architecture/domain-model.md`.

## 0. Preconditions

The backend scaffold may not exist yet (issue `[E0-1]`). Locate the migration folder rather than assuming it:
```bash
find backend -path '*db/migration*' -name '*.sql' 2>/dev/null | head; ls backend/src/main/resources/db/migration 2>/dev/null
```
If there is no backend or no migration folder, stop and tell the user. Don't create the scaffold as a side effect.

## Steps

1. **Next version comes from the files, not from docs.**
   ```bash
   ls backend/src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1
   ```
   Next = that + 1. If the repo uses a different scheme (e.g. timestamps), follow what is there. Never reuse or skip a number.

2. **Read the design and the existing migrations** that touch the same tables. Check the migration order in `database-design.md`. A table must not land before the tables it references.

3. **Write `V{n}__{snake_case_summary}.sql`** in PostgreSQL syntax, following the project's schema rules:
   - `id UUID` primary keys, `timestamptz` (UTC) for instants, `NUMERIC(14,2)` for money. Never `float`/`double`/`money`.
   - Every business table has `organization_id NOT NULL`. Where the design calls for same-org integrity, use **composite FKs** `(organization_id, x_id) → parent(organization_id, id)` backed by a unique `(organization_id, id)` on the parent.
   - Enforce invariants **in the database**: partial unique indexes (e.g. one open `bed_assignment` per bed, one active admission per patient) and exclusion constraints (no overlapping booked appointments per practitioner, which needs `btree_gist`). Application checks alone are not enough.
   - Lifecycle `status` columns with `CHECK` constraints instead of hard deletes for clinical or financial data.
   - Immutable tables (issued invoices, payments, refunds, credit notes, audit events) must not be designed for UPDATE/DELETE. Follow whatever enforcement `database-design.md` specifies (e.g. triggers or grants).
   - Optimistic-lock `version` column on aggregates the API updates.
   - Index every FK column and every column used in an org-scoped lookup (`idx_<table>_<cols>`).
   - Add a short `--` comment only where the reason isn't obvious from the SQL (e.g. why a constraint exists).

4. **Never edit a migration that has been merged.** Fix problems forward in a new file. A merged migration has been checksummed, and changing it breaks every existing database.

5. **Keep the mapping in sync.** If a JPA entity maps the change, update it in the owning module's `domain`/`infrastructure` package. Never touch another module's entities. Money maps to `BigDecimal`, and `version` maps to `@Version`.

6. **Verify against real Postgres.** Find the schema-validation / migration test rather than guessing its name:
   ```bash
   grep -rlE 'Flyway|Testcontainers|PostgreSQLContainer' backend/src --include=*.java | head
   ```
   Run it, then the module's tests (from `backend/`; `./gradlew.bat` on Windows):
   ```bash
   ./gradlew integrationTest --tests "<that test>"
   ./gradlew test
   ```
   This needs Docker. If Docker is down, or no Postgres-backed migration test exists, say so plainly and don't report the migration as verified. For a constraint that enforces an invariant, add or extend an integration test proving the DB rejects the violating row.

7. **Report** the file name, the SQL, the constraints added and the invariant each one enforces, the test results, and any gap between the migration and `database-design.md`. Don't edit the design doc unless asked. Point out the drift instead.

Use synthetic data only in any seed or test rows.
