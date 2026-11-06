# Hospital Management System (HMS)

A modern, modular Hospital Management System for small and medium multi-specialty clinics and nursing homes in India — designed to grow from a single clinic into a single hospital, and later a multi-tenant SaaS platform.

> **Status: planning / pre-implementation.** The product and architecture foundation is drafted and awaiting review. There is no runnable application yet; sections marked *(planned)* describe the intended setup.

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

> If `docs/` is not present yet, the documents are being moved into this repository from the project workspace.

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

## Getting started *(planned)*
Prerequisites: JDK 21, Node.js (LTS), Docker with Compose.
```bash
# start PostgreSQL and supporting services
docker compose -f infra/compose/compose.yml up -d

# backend
cd backend && ./gradlew build && ./gradlew bootRun

# frontend
cd frontend && npm ci && npm run dev
```
Configuration is read from environment variables; copy `.env.example` to `.env` for local development. These commands will be confirmed here once the scaffold (`[E0-1]`) lands.

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
