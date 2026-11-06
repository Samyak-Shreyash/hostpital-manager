# CLAUDE.md — Hospital Management System (HMS)

Guidance for Claude Code (and any AI assistant) working in this repository. Read this first, then the docs it points to.

## 1. What this is
A commercial-grade Hospital Management System for small/medium multi-specialty clinics and nursing homes in **India**. Release 1 = OPD + basic IPD + billing + reporting for **one facility, ~50 concurrent users**. Long-term path: single hospital → multi-tenant SaaS. Do not build future-stage features now, but do not make choices that block them.

Solo developer + AI assistance. **Operational simplicity is a first-class constraint**: no Kubernetes, Kafka, Redis, microservices, or extra infra without a documented requirement.

## 2. Current status (update this section as work lands)
- Phase: **foundation documents drafted, pending owner review/approval.** No application code exists yet.
- Do **not** start implementing features until the foundation docs are approved and the relevant backlog issue is chosen.
- Backlog: GitHub issues titled `[E<epic>-<n>] …` (86 issues, milestones M0–M6). Issue list source: `issues.json`; created with `Create-Issues.ps1` (idempotent, matches by `[ID]` prefix).
- Before any task: inspect the repo, read the relevant docs, check open/closed issues. **Never assume something exists — verify it.**

## 3. Confirmed decisions (do not reopen; changes need a documented ADR + owner approval)
| Area | Decision |
|---|---|
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA/Hibernate, Bean Validation, Actuator, springdoc OpenAPI |
| Database | PostgreSQL, Flyway (forward-only), UUID ids, UTC `timestamptz`, `NUMERIC` money |
| Build/test | Gradle, JUnit 5, Mockito, Testcontainers |
| Frontend | React + TypeScript, Tailwind, React Router, TanStack Query, React Hook Form, Zod |
| Architecture | **Modular monolith**, REST `/api/v1` |
| Ops | Docker, Docker Compose (dev), GitHub Actions, reverse proxy, Prometheus + Grafana, structured logs |
| Compliance direction | DPDP Act 2023/Rules 2025, ABDM (FHIR R4 at the boundary only) |

Proposed-but-unapproved items live in `docs/adr/` with status **Proposed** (e.g. server-side sessions instead of JWT, Spring Modulith, RLS as defence in depth, UUIDv7). Treat Proposed ADRs as *not yet binding* — ask before depending on them.

## 4. Architecture rules (enforced by tests where possible)
- Modules: identity, organization, patient, practitioner, appointment, clinical, admission, bedmanagement, billing, payment, audit, reporting (+ `shared` kernel).
- Per module packages: `api` (the **only** package other modules may import), `application` (use cases, `@Transactional` boundary), `domain`, `infrastructure` (JPA repos/adapters), `web` (controllers + DTOs).
- **Never** import another module's repositories, entities or internal packages. Cross-module references are by ID. Reporting reads SQL views (documented exception).
- Dependency direction per `docs/architecture/hld.md` §4. Admission supplies charges to billing through billing's `ChargeSource` port (never billing → admission).
- Transactions start at application services only. No remote calls inside a DB transaction. Lock order: bed → admission → invoice.
- Avoid over-engineering: prefer the simplest design that satisfies current requirements.

## 5. Domain rules that must never be violated
- One **Patient** identity per person; visits/admissions/invoices attach to it. **Encounter ≠ Admission** — never merge them.
- A bed has **at most one open BedAssignment**; a patient has at most one active admission; a practitioner has no overlapping booked appointments. Enforce with DB constraints (partial unique indexes / exclusion constraints) **plus** row locks — not application checks alone.
- **Money**: `NUMERIC(14,2)` / `BigDecimal` via a `Money` value object, never `float/double`; JSON money = decimal **strings**; rounding half-up at line level.
- **Issued invoices, payments, refunds, credit notes, audit events are immutable** (no UPDATE/DELETE; corrections via credit notes/refunds). Invoice numbers are gapless per org per financial year, assigned at issue under a locked counter.
- Finalized encounters/prescriptions/discharge summaries are immutable; changes are addenda/new versions.
- No hard deletes of clinical or financial data; use lifecycle status.
- Every business row has `organization_id`. **`organization_id` alone is not tenant isolation** — also scope every query by the authenticated org, use composite same-org FKs where specified, and keep the isolation test suite passing. Never take the org from request parameters.

## 6. Security & privacy (non-negotiable)
- Authorization is **permission-based** (`patient:read`, `billing:refund`…), never role-name checks in business code. Every endpoint needs `@PreAuthorize`; a test fails the build if one is missing.
- Audit sensitive reads and writes (actor, action, entity, timestamp, change metadata of field *names*, correlation id). Audit writes share the mutation's transaction. **Never** log or audit passwords, tokens, or clinical free text / unnecessary PHI.
- **Use synthetic data only** — in tests, fixtures, seeds, screenshots, docs, issues, commit messages. No real names, phone numbers, ABHA numbers, or patient records, ever.
- Secrets never in git (`.env.example` documents variables; real values come from env/secret store). No new dependency without a clear purpose; justify it in the PR.
- Do not claim legal/regulatory compliance. Flag anything needing legal review (retention periods, consent wording, GST treatment, ABDM certification) instead of assuming.

## 7. Working agreement
1. Pick work from the backlog by **dependency order**, not by ease: phase → architectural dependencies → value → technical risk → blockers → readiness.
2. For each issue: read requirements → inspect existing code/docs → propose approach (ask if it changes architecture, security or behaviour) → implement → **add automated tests** → update docs/OpenAPI → verify acceptance criteria → update the issue.
3. Small PRs, short-lived branches, conventional commits (`feat(patient): …`, `fix(billing): …`). Reference the issue ID (`[E3-1]`).
4. Never silently change an architectural decision; write or update an ADR.
5. **Do not claim work that was not performed.** Say "tests passed" only after running them; say what was and wasn't verified.
6. Ask a question only when the answer materially changes architecture, security or product behaviour; otherwise pick a low-risk default and record it as an assumption.

### Definition of Done (every issue)
Code + automated tests passing in CI · audit coverage where sensitive · Flyway migration reviewed · OpenAPI/docs updated · no new high-severity scan findings · synthetic data only · acceptance criteria verified.

### Required test focus
Concurrent bed allocation · duplicate patient registration · appointment conflicts · concurrent payments · invoice calculation accuracy · unauthorized patient access · cross-organization access (every endpoint returns 404 for another org's ids).

## 8. Planned layout & commands
> The scaffold does not exist yet (issue `[E0-1]`). Until it does, treat the commands below as the **intended** conventions and verify before relying on them.

```
backend/   Gradle (single project, modules as packages), src/{main,test,integrationTest,architectureTest}
frontend/  Vite + React + TS (feature-based folders)
e2e/       Playwright critical journeys     perf/  k6 scenarios
infra/     docker, compose, proxy, monitoring, backup
docs/      product, architecture, adr, api, engineering, operations, project-management
```
Intended commands (confirm in `README.md` once scaffolded):
- Backend: `./gradlew build`, `./gradlew test`, `./gradlew integrationTest` (needs Docker), `./gradlew architectureTest`
- Frontend: `npm ci`, `npm run dev`, `npm run build`, `npm test`, `npm run lint`
- Stack: `docker compose -f infra/compose/compose.yml up`

## 9. Documentation map
Source of truth for design — read before coding in that area, and keep in sync:
| Topic | File |
|---|---|
| Product scope, requirements, business rules (BR-*) | `docs/product/prd.md` |
| Entities, aggregates, invariants, lifecycles | `docs/architecture/domain-model.md` |
| Schema, constraints, concurrency, migration order | `docs/architecture/database-design.md` |
| C4, module dependencies, flows | `docs/architecture/hld.md` |
| Decisions (ADR-0001…0012) | `docs/adr/` |
| REST contracts, errors, permissions | `docs/api/api-design.md` |
| Roadmap, milestones, backlog | `docs/project-management/` |
| Reconciliation notes & open questions | `docs/00-review-notes.md` |
| Original charter (superseded where it conflicts) | `docs/00-charter-and-architecture.md` |

> These documents currently live in the Claude project “Hospital Management”; they should be committed under `docs/` in this repo. If `docs/` is missing, ask the owner before inventing content.

## 10. API conventions (summary — full detail in `docs/api/api-design.md`)
`/api/v1` · JSON camelCase · UTC ISO-8601 instants · RFC 7807 errors with stable `code` · pagination `page/size` (max 100) · optimistic locking via `version` → `409 CONCURRENT_MODIFICATION` · `Idempotency-Key` on payments/refunds/invoice issue · cross-org ids → `404` · session-cookie auth with CSRF token (pending ADR-0005 approval).

## 11. Repo housekeeping
- `Create-Issues.ps1` + `issues.json`: one-off backlog bootstrap (needs `gh auth login` and `gh auth refresh -s project`). Re-running is safe; do not edit titles' `[ID]` prefixes.
- Keep this file short and current. When a convention changes, update it in the same PR.
