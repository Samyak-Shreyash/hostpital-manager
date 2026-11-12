# Risk register

> Seeded from `docs/product/prd.md` section 12. L = likelihood, I = impact (L/M/H). Review at each milestone exit. Owner for all: project owner (solo).

| ID | Risk | L | I | Mitigation | Linked issues | Status |
|---|---|---|---|---|---|---|
| R1 | Solo bandwidth alongside full-time role | H | H | Weekly time budget, thin slices, scope cut order (`roadmap.md` section 5) | — | Open |
| R2 | R1 scope (OPD+IPD+billing) larger than charter's OPD-only MVP | H | H | Milestone gating; IPD only after OPD billing is stable | M3, M4 | Open |
| R3 | Billing/GST misinterpretation | M | H | Accountant review; GST fields optional | `[E7-1]`, `[E10-8]` | Open |
| R4 | Clinical safety (wrong patient) | M | H | Duplicate detection, patient banner on every screen, immutable records | `[E3-3]`, `[E3-6]`, `[E6-2]` | Open |
| R5 | PHI breach | M | H | Least privilege, audit, pen test before real PHI | `[E1-*]`, `[E10-4]`, `[E10-9]` | Open |
| R6 | Regulatory misread | M | H | Counsel review before pilot with real data | `[E10-8]` | Open |
| R7 | No design partner to validate workflows | M | H | Secure a pilot before M3 | `[E10-7]` | Open |
| R8 | Backup never restore-tested | M | H | Restore drill is a go-live gate | `[E10-2]` | Open |
| R9 | RLS with pooled connections proves too complex | M | M | `[E1-2]` spike; ADR-0010 allows a documented downgrade to app-level scoping + isolation tests | `[E1-2]`, `[E1-6]` | Open |
