# ADR-0007: Audit architecture
- **Status:** Accepted (2026-11-12, owner)
- **Decision:** `audit` module with `AuditRecorder` port. Writes: recorded in same transaction as the change (atomic). Clinical reads: recorded in separate short transaction, fail-closed. Append-only table (triggers + DB privileges), monthly partitions, correlation id from MDC. Metadata excludes passwords, tokens, and clinical free text. Optional hash chain for tamper evidence (S). Audit is not application logging; logs are PHI-redacted separately.
- **Options rejected:** Hibernate Envers (stores full entity copies = PHI duplication, hard to redact, doesn't capture reads); DB triggers only (no actor/purpose context); event streaming (infra burden).
- **Consequences:** + provable who/what/when. − Read-audit doubles write load on view endpoints (acceptable at 50 users; batch if needed).
