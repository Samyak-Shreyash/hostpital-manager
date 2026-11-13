# Hospital Management System (HMS)

A modern, modular Hospital Management System for small and medium multi-specialty clinics and nursing homes in India — designed to grow from a single clinic into a single hospital, and later a multi-tenant SaaS platform.

> **Status: M0 skeleton.** The application builds and starts, but has no features yet.

## Release 1 scope
| Area | Capabilities |
|---|---|
| Identity & access | Login/logout, role- and permission-based access, user management, session management, audit logging |
| Patients | Registration, unique patient ID (UHID), demographics, contacts, allergies, medical history, search, duplicate detection |
| Doctors & staff | Doctors with multiple specialties/departments, availability and consultation schedules, staff records |
| Appointments | Booking, rescheduling, cancellation, check-in, walk-ins, consultation queue |
| OPD | Encounters, vitals, notes, diagnosis, treatment plan, prescriptions, follow-up |
| IPD (basic) | Admission, wards and configurable beds, allocation, transfer, discharge summary, accommodation charges |
| Billing | OPD/IPD invoices, partial payments, refunds, receipts, printable invoices |
| Reporting | Registrations, appointments, consultations, active admissions, bed occupancy, discharges, daily revenue, outstanding payments |

**Out of scope for Release 1:** laboratory, pharmacy/inventory, emergency department, insurance/TPA, online payments, patient portal, telemedicine, live ABDM integration, multi-facility and multi-tenant onboarding.

## Technology
| Layer | Choice |
|---|---|
| Backend | Java 21, Spring Boot, Spring Security, Spring Data JPA / Hibernate, Flyway, springdoc OpenAPI |
| Database | PostgreSQL |
| Frontend | React, TypeScript, Tailwind CSS, React Router, TanStack Query, React Hook Form, Zod |
| Build & test | Gradle, JUnit 5, Mockito, Testcontainers, Playwright (E2E), k6 (performance) |
| Delivery | Docker, Docker Compose, GitHub Actions, Prometheus, Grafana |
| Architecture | Modular monolith, REST API (`/api/v1`) |

## Architecture at a glance
One deployable Spring Boot application with enforced module boundaries (identity, organization, patient, practitioner, appointment, clinical, admission, bed management, billing, payment, audit, reporting) on a single PostgreSQL database. Key principles:
- Invariants (one open allocation per bed, no overlapping appointments, immutable posted invoices and payments) are enforced in the **database**, not just application code.
- Money uses exact decimals; posted financial records are corrected through credit notes and refunds, never edited.
- Sensitive reads and writes are audited; patient data in logs is redacted.
- Every business record carries an organization reference; tenant isolation is hardened in later stages.

See `docs/architecture/hld.md` for diagrams and flows.

## Documentation
Design documents are the source of truth and are kept in sync with the code.

| Topic | Location |
|---|---|
| Requirements, personas, business rules | `docs/product/prd.md` |
| Domain model and ER diagram | `docs/architecture/domain-model.md` |
| Database design and concurrency decisions | `docs/architecture/database-design.md` |
| High-level architecture (C4, flows) | `docs/architecture/hld.md` |
| Architecture decision records | `docs/adr/` |
| REST API design | `docs/api/api-design.md` |
| Roadmap, milestones, backlog | `docs/project-management/` |
| Open questions and reconciliation notes | `docs/00-review-notes.md` |
| Project structure | `docs/engineering/project-structure.md` |

> `docs/00-charter-and-architecture.md` (original charter) is not yet committed; see `docs/00-review-notes.md` section 4.

## Roadmap
| Milestone | Goal |
|---|---|
| M0 | Walking skeleton deployed to staging |
| M1 | Secure core: authentication, RBAC, audit, organization isolation |
| M2 | Master data, patients, doctors, availability |
| M3 | OPD slice: book → consult → prescribe → invoice → pay |
| M4 | Basic IPD: admit, bed allocation, transfer, discharge, final bill |
| M5 | Reporting |
| M6 | Production readiness: load tests, security review, backup/restore drill, UAT |

Work is tracked as GitHub issues titled `[E<epic>-<n>] …`, grouped by epic and milestone on the project board.

## Getting started
Prerequisites: JDK 21, Node.js 22+ (see `frontend/.nvmrc`), Docker (needed for PostgreSQL and the integration tests).

```bash
# Whole stack in Docker (Postgres + API + proxy serving the SPA), from the repo root:
cp .env.example .env                              # then fill in every value (compose refuses to start with blanks)
# DB roles (ADR-0015) are created on first start of an empty volume; a volume from before [E0-6] needs one `... down -v`.
docker compose -f infra/compose/compose.yml --env-file .env up -d --build --wait
# -> http://localhost:8080 (SPA; /api/* goes to the backend). Stop: ... down  (add -v to wipe the database)
# Monitoring ([E0-7]): add `--profile monitoring` to up/down. Prometheus scrapes the API service at /actuator/prometheus
# inside the network (the proxy never routes /actuator); Grafana with the "HMS overview" dashboard on
# http://localhost:${HMS_GRAFANA_PORT}.

# Or develop locally against just the database:
# 1. PostgreSQL
docker compose -f infra/compose/compose.yml --env-file .env up -d db

# 2. Backend: full build (unit + integration + architecture tests), then run
cd backend
./gradlew build                                   # Windows: gradlew.bat build
# SPRING_PROFILES_ACTIVE=dev supplies public built-in field-encryption keys (ADR-0009); any other profile
# needs HMS_FIELD_ENCRYPTION_KEYS and HMS_ABHA_HMAC_KEY (see .env.example) or startup fails.
SPRING_PROFILES_ACTIVE=dev HMS_DB_PASSWORD=<from .env> HMS_DB_MIGRATION_PASSWORD=<from .env> ./gradlew bootRun
# -> http://localhost:8080/actuator/health returns {"status":"UP"}

# 3. Frontend (separate terminal)
cd frontend
npm ci
npm run dev                                       # -> http://localhost:5173
```

| Task | Command |
|---|---|
| Backend unit tests (no Docker) | `./gradlew test` |
| Backend integration tests (Testcontainers) | `./gradlew integrationTest` |
| Module-boundary rules (ArchUnit) | `./gradlew architectureTest` |
| Frontend lint / test / build | `npm run lint` · `npm test` · `npm run build` |
| Proxy + SPA Docker image (repo root) | `docker build -f infra/docker/proxy.Dockerfile -t hms-proxy:$(git rev-parse HEAD) .` |
| Backend Docker image (repo root) | `docker build -f infra/docker/backend.Dockerfile -t hms-backend:$(git rev-parse HEAD) backend` |

CI (`.github/workflows/ci.yml`) runs on every PR and push to `main`: `backend` (Gradle build incl. integration and architecture tests), `frontend` (lint, test, build), `image` (Trivy dependency scan, backend image tagged with the commit SHA, Trivy image scan) and `compose` (dev stack incl. monitoring profile comes up healthy, Prometheus scrapes the API). Scans fail on fixable HIGH/CRITICAL findings. All four are required status checks on `main` (admins included), so a red PR cannot merge.

Configuration is read from environment variables; all variables are documented in `.env.example`. Copy it to `.env` for local use (git-ignored). Repository layout and build decisions: ADR-0013.

### First organization

On a fresh database, set `HMS_BOOTSTRAP_ORG_NAME`, `HMS_BOOTSTRAP_ADMIN_EMAIL` and
`HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE=/var/lib/hms/bootstrap/admin-password.txt` in `.env` (see `.env.example`), then start the
stack. The API creates the organization, default roles and a System Administrator; the temporary password is written only to
that file on the `bootstrap-secret` volume:

```bash
docker compose -f infra/compose/compose.yml --env-file .env exec api cat /var/lib/hms/bootstrap/admin-password.txt
```

Log in, change the password, then **delete the file**
(`... exec api rm /var/lib/hms/bootstrap/admin-password.txt`). Once an organization exists the bootstrap is a no-op, so
restarts work with or without these variables.

## Backlog bootstrap
The repository includes a one-off tool to create the planned issues:
```powershell
gh auth login
gh auth refresh -s project
.\Create-Issues.ps1 -DryRun   # preview
.\Create-Issues.ps1           # create labels, milestones, issues and add them to the project board
```
It is safe to re-run: existing issues are matched by their `[ID]` prefix and skipped.

## Security and privacy
This software is intended to handle sensitive health and financial data.
- Use **synthetic data only** in development, tests, screenshots and documentation. Never commit real patient information or secrets.
- Report security issues privately to the maintainer rather than in public issues.
- No claim of legal or regulatory compliance (DPDP Act, ABDM, clinical regulations) is made; compliance requires separate legal review and certification.

## Contributing
Currently a solo project developed with AI assistance. Guidelines for contributors and AI assistants are in `CLAUDE.md`. Use conventional commits, small pull requests, and reference the issue ID.

## License
Not yet decided. All rights reserved until a license is added.
