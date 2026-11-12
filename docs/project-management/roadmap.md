# Roadmap and milestones

> Backlog of record: GitHub issues titled `[E<epic>-<n>] …` with milestones M0–M6 (bootstrapped by `Create-Issues.ps1`). This file summarises it; when they disagree, the issue wins and this file is updated. Status as of 2026-11-12.

## 1. Milestones

| Milestone | Goal | Epics | Exit gate | Status |
|---|---|---|---|---|
| M0 Skeleton | Build, test, run and observe an empty app | E0 | CI green; Compose stack up; baseline migration; ADRs accepted | 6 closed, 4 open (see section 3) |
| M1 Secure core | Login, RBAC, org isolation, audit | E1 | `@PreAuthorize` coverage test; two-org isolation suite; append-only audit | Not started |
| M2 Master data & patients | Org settings, catalog, patients, practitioners | E2, E3, E4 | Duplicate detection; read audit fail-closed | Not started |
| M3 OPD slice | Appointment → consult → Rx → invoice → payment | E5, E6, E7 | `[E7-M3]` Playwright journey green | Not started |
| M4 IPD basic | Admission, beds, transfers, discharge, IPD billing | E8 | `[E8-9]` E2E; concurrent bed allocation suite | Not started |
| M5 Reporting | Operational, IPD and finance reports | E9 | Reports reconcile with source data | Not started |
| M6 Production readiness | Backups, load, security, legal, UAT | E10 | Restore drill, p95 < 500 ms at 50 users, pen test, legal review package | Not started |

Milestones are sequential gates. Within a milestone, order by dependency, not issue number.

## 2. Epics

| Epic | Title | Milestone |
|---|---|---|
| E0 (#1) | Platform foundation | M0 |
| E1 (#2) | Identity, access & audit core | M1 |
| E2 (#3) | Organization & catalog | M2 |
| E3 (#4) | Patient management | M2 |
| E4 (#5) | Practitioner & availability | M2 |
| E5 (#6) | Appointments & queue | M3 |
| E6 (#7) | OPD clinical | M3 |
| E7 (#8) | Billing & payments | M3 |
| E8 (#9) | Bed management & IPD | M4 |
| E9 (#10) | Reporting | M5 |
| E10 (#11) | Hardening & production readiness | M6 |

## 3. Open M0 items

| Issue | Note |
|---|---|
| `[E0-9]` #20 Finalise ADRs | Acceptance criteria met: ADR files split under `docs/adr/`, all 0001–0015 Accepted. Ready to close. |
| `[E0-4]` #15 Spring Modulith spike | Superseded by ADR-0001 (ArchUnit only, Modulith deferred); `ArchitectureTest` exists. Close as superseded; the `verify()` criterion no longer applies. |
| `[E0-8]` #19 Staging deploy | Needs a hosting target; hosting is an open ADR candidate (`docs/adr/README.md`) and `[E10-1]` decides hosting + backups. Does not block M1. |
| `[E0]` #1 Epic | Close when the above are resolved. |

## 4. Critical path (next)

1. `[E1-2]` RLS feasibility spike → confirms or downgrades ADR-0010 mechanism.
2. `[E1-1]` Audit core (same-transaction audit, ADR-0007).
3. `[E1-5]` RBAC → `[E1-3]` authentication → `[E1-6]` org context + isolation harness.
4. `[E1-4]`, `[E1-7]`, `[E1-8]`, `[E1-9]`, `[E1-10]`.

Spikes that gate later work: `[E6-4]` PDF library (before `[E6-5]`, `[E7-5]`), `[E10-1]` hosting/backup (before `[E0-8]` staging and `[E10-2]`).

## 5. Scope cut order (if time runs short)

Cut from the end, never the integrity work: `[E1-10]` TOTP MFA (defer to post-pilot), `[E9-4]` CSV export, `[E7-8]` discounts, `[E4-4]` availability UI polish. Never cut: concurrency constraints, audit, isolation tests, backups/restore drill, legal review package.

Risks: `risk-register.md`.
