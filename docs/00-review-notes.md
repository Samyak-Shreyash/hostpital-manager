# Review notes and open questions

> Reconciliation log across the design documents. Add an entry when documents disagree or a question is deferred; close it by pointing to the document or ADR that settled it. Last reviewed 2026-11-12.

## 1. Decisions settled

| Date | Topic | Outcome |
|---|---|---|
| 2026-11-07 | Module boundary checks | ADR-0001: ArchUnit only, Spring Modulith deferred. Supersedes `[E0-4]`. |
| 2026-11-07 | IDs | ADR-0004: UUIDv7, in-house generator; timestamp exposure accepted risk. |
| 2026-11-12 | ADR approval (`[E0-9]`) | ADRs 0005–0012 accepted by owner; all of 0001–0015 now Accepted. Stale "Proposed" references in `database-design.md`, `hld.md`, ADR-0015 and CLAUDE.md updated. |
| 2026-11-12 | RLS feasibility (`[E1-2]`, was Q3) | RLS kept; usage rules recorded in ADR-0010 spike outcome. |
| 2026-11-12 | Charter deviations | Accepted deviations from the original charter: server-side sessions instead of JWT (ADR-0005), `organization_id` instead of `tenant_id` (ADR-0010), `NUMERIC(14,2)` instead of integer paise (ADR-0012). |

## 1a. M2 decisions (2026-11-13)

Owner decisions for the unimplemented M2 issues. Already settled in merged code (do not reopen): org settings + bootstrap under advisory lock (`V7`, database-design §16), departments/specialties (`V8`), versioned service prices with GiST exclusion (`V9`, §18), patient + gapless UHID `HMS-` (`V10`, §19), `pg_trgm` for name search (database-design §3).

| Issue | Topic | Decision |
|---|---|---|
| `[E3-3]` | Duplicate threshold (BR-P3) | Candidate if same DOB and trigram similarity ≥ 0.45, or same normalized phone and similarity ≥ 0.30. Thresholds stored in organization settings (org-tunable). |
| `[E3-2]` | ABHA number | Stored encrypted (ADR-0009) plus an HMAC keyed hash column for exact-match search. Format check: 14 digits only, unverified. API returns it masked (last 4). |
| `[E3-2]`, `[E3-3]` | Phone normalization | E.164; a bare 10-digit input gets `+91`. Search and duplicate matching use the normalized value. Landlines not supported in R1. |
| `[E3-5]` | Editable fields | All fields except UHID. Contact/address need `patient:update`. DOB, sex and name need `patient:update-identity` plus a reason. Audited by field name. |
| `[E3-7]` | Read audit | Fail-closed on detail reads (`GET /patients/{id}` and sub-resources): the audit write shares the transaction, so a failed audit write fails the read. A search writes one `PATIENT_SEARCH` event with the result count, not one per patient. |
| `[E2-4]` | Seed data | Idempotent Gradle task behind `dev`/`staging` profiles, refused under `prod`. Default: 1 org, ~10 staff, 2k patients. `--large` gives 100k patients for the `[E3-2]` p95 check. Synthetic names from a fixed list. |
| `[E4-2]` | Staff login | Every staff record links 1:1 to a user (consistent with Q6). Roles assigned via the existing `role:assign` path (`[E1-7]`). |
| `[E4-3]` | Leave exceptions | A date plus an optional time range (full day or partial block). Existing bookings that clash are flagged, not auto-cancelled. |

New permission implied: `patient:update-identity` (add to the catalogue in the `[E3-5]` migration).

Legal review flags (for `[E10-8]`, not decided here): ABHA retention and masking policy; whether a patient-search audit event needs the query terms (currently excluded as PHI); duplicate-candidate payload shows full name and DOB (phone masked) to users with patient:create — confirm acceptable under DPDP minimisation.

### M2 → M1 hard dependencies

| M2 issue | Depends on |
|---|---|
| `[E2-4]` seed | `[E1-5]` RBAC roles, `[E1-6]` org context |
| `[E3-2]` search, `[E3-3]` duplicates | `[E1-6]` RLS; `[E1-5]` permissions; `[E3-3]` override audited → `[E1-1]` |
| `[E3-4]` allergies, `[E3-5]` update | `[E1-1]` audit, `[E1-5]`, `[E1-6]` |
| `[E3-6]` patient UI | `[E1-8]` admin UI shell (login, route guards) |
| `[E3-7]` read audit | `[E1-1]` audit core (fail-closed recorder) |
| `[E4-1]` doctors, `[E4-2]` staff | `[E1-7]` user management (user link), `[E1-5]` |
| `[E4-3]` availability | `[E4-1]` |
| `[E4-4]` UI | `[E1-8]`, `[E4-1]`..`[E4-3]` |

## 2. Open questions

| ID | Question | Raised in | Blocks | Default until answered |
|---|---|---|---|---|
| Q1 | Bed-day charging rule (midnight census, same-day admit/discharge) | PRD BR-B6 | `[E8-5]` | [A] charge per midnight occupied, admission day charged, discharge day not, minimum 1 day; configurable per org |
| Q2 | GST treatment of services (most clinical services exempt) | PRD BIL-10 | `[E7-1]` | Capture line tax rate/amount; GST fields optional |
| Q4 | Hosting provider and backup tooling (managed PG vs pgBackRest) | `docs/adr/README.md` candidates, PRD A8 | `[E0-8]`, `[E10-2]` | Decided by `[E10-1]` |
| Q5 | PDF generation library | ADR candidates | `[E6-5]`, `[E7-5]` | Decided by `[E6-4]` |
| Q6 | Practitioners without a login | domain-model.md | — | [A] deferred; every practitioner has a user |
| Q7 | Time zone handling for daily charges | ADR candidates | `[E8-5]` | Facility time zone from org settings |

## 3. Legal / regulatory review required (no compliance claimed)

Tracked for `[E10-8]`: lawful basis for health-data processing (clinic as fiduciary/processor), consent notice wording, retention periods (state clinical-establishment rules, medico-legal), DPDP Rules commencement dates, ABDM certification path, GST treatment (Q2). See PRD section 11.

## 4. Known gaps and drift

- `docs/00-charter-and-architecture.md` is referenced by CLAUDE.md, the PRD (section 11) and the design-docs skill but is **not committed**. It lives in the "Hospital Management" Claude project; the owner needs to commit it. Do not reconstruct it.
- `issues.json` and `Create-Issues.ps1` are referenced by CLAUDE.md section 11 but are not in the working tree. GitHub issues are the backlog of record.
- ADRs 0002–0012 are condensed (some lack Problem/Options sections). Expand an ADR when it is next revised.
- Backend modules are empty package skeletons; only `shared` has code. Docs describing module behaviour are design, not implementation.
