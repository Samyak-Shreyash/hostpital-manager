# ADR-0013: Repository and build layout
- **Status:** Accepted (2026-11-07, owner): repo shape **A** (monorepo, native tools per folder), backend build **A** (single Gradle project, modules as packages), Groovy DSL + version catalog, Vitest + ESLint.
- **Context:** `[E0-1]` needs a repo skeleton whose build and tests run identically locally and in CI. Solo developer: one repo, minimal tooling, no build orchestrator. Module boundaries (ADR-0001) are packages, not separate builds. Directory skeleton already committed: `backend/`, `frontend/`, `e2e/`, `perf/`, `infra/`, `docs/`, `.github/workflows/`.
- **Problem:** Choose repo shape, backend build structure, test separation, frontend tooling and CI layout.
- **Options:**
  - Repo: (A) monorepo, one folder per deliverable, no root build tool; (B) monorepo with a root orchestrator (Nx/Gradle composite); (C) separate backend/frontend repos.
  - Backend build: (A) single Gradle project, modules as packages under `com.hms.<module>`; (B) one Gradle subproject per module.
  - Gradle DSL: Groovy `build.gradle` vs Kotlin `build.gradle.kts`.
- **Decision:**
  - **Repo:** A. Each top-level folder builds independently with its own native tool; docs live with code so they change in the same PR.

    | Folder | Contents | Tool |
    |---|---|---|
    | `backend/` | Spring Boot app, base package `com.hms`, one package per module (`api`/`application`/`domain`/`infrastructure`/`web`) + `shared` | Gradle wrapper (committed) |
    | `frontend/` | Vite + React + TS SPA, feature folders (`src/app`, `features`, `components`, `api`, `lib`) | npm, committed `package-lock.json` |
    | `e2e/` | Playwright critical journeys | npm (own `package.json`) |
    | `perf/` | k6 scenarios | k6 |
    | `infra/` | `docker/`, `compose/compose.yml`, `proxy/`, `monitoring/`, `backup/` | Docker Compose |
    | `docs/` | Design docs (source of truth) | — |
  - **Backend build:** single Gradle project (option A). Boundaries enforced by tests, not by Gradle (ADR-0001); per-module subprojects add build config with no benefit at this size and can be introduced later if compile isolation is wanted.
  - **Gradle:** Groovy DSL (`build.gradle`, `settings.gradle`) — owner's choice: most examples and documentation use it, and the single-project build is small enough that Kotlin DSL's type checking adds little; dependency versions in a version catalog `gradle/libs.versions.toml`; Java toolchain 21; Spring Boot dependency management for Spring artifacts.
  - **Test source sets** (JVM Test Suite plugin), each its own Gradle task:

    | Source set | Task | Content | Needs Docker |
    |---|---|---|---|
    | `src/test` | `test` | unit tests (JUnit 5, Mockito); fast, no Spring context unless sliced | no |
    | `src/integrationTest` | `integrationTest` | Testcontainers PostgreSQL: repositories, constraints, Flyway, HTTP + security, isolation suite | yes |
    | `src/architectureTest` | `architectureTest` | ArchUnit boundary rules, `@PreAuthorize`-on-every-endpoint rule | no |

    `check` depends on all three; `build` therefore runs them all. `test` alone stays Docker-free for quick local feedback.
  - **Migrations:** `backend/src/main/resources/db/migration`, Flyway forward-only.
  - **Frontend:** Vite, npm (no pnpm/yarn — one fewer tool), Node pinned to the current LTS major via `.nvmrc` and `engines`; scripts `dev`, `build`, `test` (Vitest + React Testing Library, sharing Vite's config), `lint` (ESLint flat config with typescript-eslint and react-hooks rules), `typecheck`.
  - **CI:** one GitHub Actions workflow `.github/workflows/ci.yml` on PR and push to `main`, two parallel jobs: `backend` (`./gradlew build`, Docker available on `ubuntu-latest` runners) and `frontend` (`npm ci && npm run lint && npm test && npm run build`). Gradle and npm caches enabled. `e2e`/`perf` are separate workflows added by their own issues. Required status checks on `main` = both jobs.
  - **Config:** environment variables only; root `.env.example` documents every variable with synthetic/placeholder values; `.env` is git-ignored.
- **Consequences:** + Each part builds with its native, well-documented tooling; no orchestrator to learn or maintain; one PR can change code, migration and docs together. + Docker-free `test` keeps the inner loop fast. − Full `build` needs Docker locally (documented in README). − No cross-folder build graph: changes to the API contract are not automatically checked against the frontend (mitigated later by OpenAPI-generated client/diff, ADR-0008). − A single Gradle project gives no compile-time module isolation; ArchUnit failures catch violations at `check` time instead.
- **Revisit triggers:** backend build regularly > ~5 min → split into Gradle subprojects (mechanical once boundaries are clean); a second team or a separately released app (e.g. patient mobile app) → consider an orchestrator or a separate repo for that app.
- **Dependencies on other decisions:** ArchUnit as the boundary checker follows ADR-0001 (accepted: ArchUnit only, Spring Modulith deferred).
