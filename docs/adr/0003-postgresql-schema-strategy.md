# ADR-0003: PostgreSQL schema strategy
- **Status:** Accepted (2026-11-07, owner)
- **Context:** Modules want isolation; tenancy is deferred; solo ops.
- **Options:** (A) one schema, ownership by naming/package; (B) schema per module; (C) schema per tenant; (D) database per tenant.
- **Decision:** A for R1: single schema `hms` (Flyway `schemas`/`default-schema` and Hibernate `default_schema` set to `hms`; the app does not use `public`). Every business row carries `organization_id`; how isolation is enforced on top of that (including whether RLS is used) is decided separately in ADR-0010 — accepting this ADR does not accept RLS. Per-tenant schema/DB is a Stage 3 decision to be revisited from real customer requirements (large enterprise tenants may demand DB-per-tenant).
- **Consequences:** + simplest migrations. − No DB-enforced module separation (enforced in code). Option B is a cheap later move if desired.
