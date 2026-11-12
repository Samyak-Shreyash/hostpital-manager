# Review notes and open questions

> Reconciliation log across the design documents. Add an entry when documents disagree or a question is deferred; close it by pointing to the document or ADR that settled it. Last reviewed 2026-11-12.

## 1. Decisions settled

| Date | Topic | Outcome |
|---|---|---|
| 2026-11-07 | Module boundary checks | ADR-0001: ArchUnit only, Spring Modulith deferred. Supersedes `[E0-4]`. |
| 2026-11-07 | IDs | ADR-0004: UUIDv7, in-house generator; timestamp exposure accepted risk. |
| 2026-11-12 | ADR approval (`[E0-9]`) | ADRs 0005–0012 accepted by owner; all of 0001–0015 now Accepted. Stale "Proposed" references in `database-design.md`, `hld.md`, ADR-0015 and CLAUDE.md updated. |
| 2026-11-12 | Charter deviations | Accepted deviations from the original charter: server-side sessions instead of JWT (ADR-0005), `organization_id` instead of `tenant_id` (ADR-0010), `NUMERIC(14,2)` instead of integer paise (ADR-0012). |

## 2. Open questions

| ID | Question | Raised in | Blocks | Default until answered |
|---|---|---|---|---|
| Q1 | Bed-day charging rule (midnight census, same-day admit/discharge) | PRD BR-B6 | `[E8-5]` | [A] charge per midnight occupied, admission day charged, discharge day not, minimum 1 day; configurable per org |
| Q2 | GST treatment of services (most clinical services exempt) | PRD BIL-10 | `[E7-1]` | Capture line tax rate/amount; GST fields optional |
| Q3 | Does RLS stay, or is ADR-0010 downgraded? | ADR-0010 | `[E1-6]` | Decided by `[E1-2]` spike |
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
