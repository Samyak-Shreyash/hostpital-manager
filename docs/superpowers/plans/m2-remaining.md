# M2 remaining issues — implementation plan

**Spec:** the GitHub issue named in each task (`gh issue view <N>`), plus `docs/00-review-notes.md` section "M2 decisions (2026-11-13)" (binding owner decisions), plus CLAUDE.md and the design docs it maps.

## Global Constraints
- CLAUDE.md sections 3–7 are binding (modular monolith, module `api` packages only, `@Transactional` on application services, org scoping + RLS, permission-based `@PreAuthorize` on every endpoint, audit in the same transaction with field names only, synthetic data only, forward-only Flyway — never edit V1–V12, next migration is V13 and numbers increase per task).
- Cross-org ids → 404. Unauthenticated → 401, missing permission → 403. Optimistic locking `version` → 409 `CONCURRENT_MODIFICATION`. RFC 7807 errors with stable `code`.
- Follow the patterns of the merged `[E3-1]` patient registration code and its tests (V10, `com.hms.patient`) and reuse its integration-test base class.
- Update `docs/api/api-design.md` and OpenAPI annotations for new endpoints.
- Verification: `cd backend && ./gradlew.bat test integrationTest architectureTest` (frontend tasks: `cd frontend && npm run lint && npm test && npm run build`).
- Conventional commits referencing the issue ID, ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Do not push.

### Task 1: [E3-2] Patient search (#36)
Implement issue #36 per its acceptance criteria and the M2 decisions (pg_trgm name search, phone normalized E.164 with +91 default, UHID exact, ABHA exact via keyed hash — encrypted storage, last-4 display, 14-digit format check). Search writes one audit event with result count (E3-7 decision).

### Task 2: [E3-3] Duplicate detection + "not a duplicate" override (#37)
Implement issue #37. Thresholds from the M2 decisions: DOB match AND name similarity ≥ 0.45, OR phone match AND name similarity ≥ 0.30; per-org configurable. Override is audited. Include the duplicate-registration concurrency test.

### Task 3: [E3-5] Patient update with change audit + optimistic locking (#39)
Implement issue #39. All fields except UHID editable; name/DOB/sex require new permission `patient:update-identity` plus a reason. Audit field names only.

### Task 4: [E3-4] Allergies & medical history (#38)
Implement issue #38: append-only entries with entered-in-error status, no hard deletes.

### Task 5: [E3-7] Read-audit for patient views, fail-closed (#41)
Implement issue #41: every patient detail read writes an audit row in the same transaction; if the audit write fails the read fails.

### Task 6: [E4-1] Doctor registration (#42)
Implement issue #42: practitioner with specialties (exactly one primary), departments, user link (by id via identity `api`).

### Task 7: [E4-2] Staff records & role assignment (#43)
Implement issue #43. Every staff record links to exactly one user; roles assigned through the existing [E1-7] role assignment path (no new role-assignment mechanism).

### Task 8: [E4-3] Weekly availability + leave + free-slot computation (#44)
Implement issue #44. Leave = date with optional time range; conflicting bookings are flagged, never auto-cancelled.

### Task 9: [E2-4] Synthetic seed data generator (#34)
Implement issue #34: idempotent Gradle task, refuses to run outside dev/staging profiles; default 2k patients, `--large` 100k. Synthetic data only.

### Task 10: [E3-6] Patient UI (#40)
Implement issue #40 in `frontend/` using the [E1-8] shell patterns: register, search, detail, patient banner component.

### Task 11: [E4-4] Doctor/staff/availability UI (#45)
Implement issue #45 in `frontend/` using the same patterns.

### Task 12: [E3-2] ABHA capture, encryption and exact search (#36, follow-up) — run right after Task 1
Owner-approved design (2026-11-13), binding:
- Shared-kernel field-encryption component: AES-256-GCM, random 96-bit nonce per value, key id stored per row; separate HMAC-SHA256 key for the lookup hash.
- Env vars: `HMS_FIELD_ENCRYPTION_KEYS` = comma-separated `keyId:base64key` pairs, first is active (others decrypt-only, for rotation); `HMS_ABHA_HMAC_KEY` = base64 key. Document both in `.env.example` (no real values). Default keys only in dev/test profiles; any other profile fails startup if missing.
- Option A: optional `abhaNumber` on patient registration (and on update once [E3-5] exists). New migration adds to `hms.patient`: `abha_encrypted`, `abha_key_id`, `abha_hash` (indexed, unique per org among non-null), `abha_last4`. Validation: exactly 14 digits (format only, no checksum/ABDM verification). API returns only last 4 digits (masked). Search: exact ABHA match via hash, joining the Task 1 search endpoint.
- Never log/audit ABHA values; audit field name `abhaNumber` only.
- Tests: round-trip encrypt/decrypt, key rotation (old key id still decrypts), tamper detection, startup failure without keys outside dev/test, masked API output, exact ABHA search, cross-org isolation.

### Task 13: Mask phone in patient search results + legal-review note (owner decision 2026-11-13) — run right after Task 3
- `GET /api/v1/patients` and `POST /api/v1/patients/search-by-abha` result items return `phoneMasked` (same masking format as the duplicate-candidate payload, e.g. `+91******0001`) instead of the full `phone`. Full phone remains available only on the patient detail endpoint. Update tests and docs/api/api-design.md.
- Add to the [E10-8] legal-review items in docs/00-review-notes.md: "Duplicate-candidate payload shows full name and DOB (phone masked) to users with patient:create — confirm acceptable under DPDP minimisation."
