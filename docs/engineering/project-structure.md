# Project structure

> Source of truth for repo shape and build: ADR-0013 (Accepted). Module rules: ADR-0001 (Accepted). This file describes the tree as committed on 2026-11-12; folders marked *(placeholder)* exist but hold no code yet.

## 1. Repository layout

| Path | Contents | Tooling |
|---|---|---|
| `backend/` | Spring Boot application, single Gradle project, modules as packages | Gradle wrapper, Groovy DSL, version catalog (`backend/gradle/libs.versions.toml`) |
| `frontend/` | Vite + React + TypeScript SPA | npm, committed `package-lock.json` |
| `e2e/tests/` | Playwright critical journeys *(placeholder)* | added by `[E7-M3]` |
| `perf/scenarios/` | k6 load scenarios *(placeholder)* | added by `[E10-3]` |
| `infra/docker/` | Dockerfiles, `db-init/` role bootstrap scripts | Docker |
| `infra/compose/` | Dev stack (`compose.yml`): Postgres, API, proxy; monitoring profile | Docker Compose |
| `infra/proxy/` | Reverse proxy config | — |
| `infra/monitoring/` | Prometheus scrape config, Grafana provisioning and dashboards (ADR-0014) | — |
| `infra/backup/` | Backup tooling *(placeholder)*, decided by `[E10-1]` | — |
| `docs/` | Design documents (see README documentation table) | Markdown, Mermaid |
| `.github/workflows/ci.yml` | CI: `backend` and `frontend` jobs, secret scan | GitHub Actions |

No root build tool. Each folder builds with its native tooling.

## 2. Backend

```
backend/src/
  main/java/com/hms/
    HmsApplication.java
    shared/            shared kernel: Money, Clock, UUIDv7 ids, Problem+JSON, correlation id, logging
    <module>/
      api/             the ONLY package other modules may import (facades, DTO records, ids, ports)
      application/     use cases; @Transactional boundary (ADR-0011)
      domain/          entities, value objects, invariants; no Spring web/JPA repository types leak out
      infrastructure/  JPA repositories, adapters
      web/             controllers + request/response DTOs; @PreAuthorize on every endpoint
  main/resources/db/migration/   Flyway V<n>__<desc>.sql, forward-only, run as hms_owner (ADR-0015)
  test/                unit tests, no Docker (`./gradlew test`)
  integrationTest/     Testcontainers PostgreSQL (`./gradlew integrationTest`)
  architectureTest/    ArchUnit rules (`./gradlew architectureTest`)
```

Modules: `identity`, `organization`, `patient`, `practitioner`, `appointment`, `clinical`, `admission`, `bedmanagement`, `billing`, `payment`, `audit`, `reporting`. All five sub-packages exist for each module. As of 2026-11-12 only `shared` holds code.

### Rules
- Cross-module references use IDs (typed id value objects) and the target module's `api` package. Never import another module's `domain`, `infrastructure`, `application` or `web`.
- Dependency direction follows `docs/architecture/hld.md` section 4. Admission → billing goes through billing's `ChargeSource` port.
- `reporting` reads SQL views (the documented exception in ADR-0001).
- New module: create the five packages, add it to the ArchUnit module list, and add it to the table above.
- Naming: `<Thing>Controller` (web), `<UseCase>Service` (application), `<Thing>Repository` (infrastructure), `<Thing>Id` (id value object).

## 3. Frontend

```
frontend/src/
  app/         app shell, router, providers (TanStack Query client)
  features/    one folder per feature (e.g. features/patients/), owns its pages, hooks, Zod schemas
  components/  shared presentational components (e.g. patient banner)
  api/         HTTP client, CSRF handling, typed endpoint functions
  lib/         framework-free helpers (money formatting, dates)
  test/        test setup
```

- IDs are opaque strings; money is a decimal string in JSON and is never parsed into `number` for arithmetic.
- A feature imports only from `components/`, `api/`, `lib/` and its own folder. It does not import another feature's folder.

## 4. Where things go

| Change | Location |
|---|---|
| Schema change | new `V<n>__*.sql` + update `docs/architecture/database-design.md` |
| New endpoint | `<module>/web` + `docs/api/api-design.md` + permission in the catalogue |
| Cross-module call | add to the owner's `api` package |
| Architecture decision | `docs/adr/NNNN-*.md`, status Proposed until approved |
| Runbook | `docs/operations/` |
