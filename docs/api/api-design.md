# HMS Release 1 — REST API Design (v0.2, draft)

Base path `/api/v1`. Full OpenAPI is generated from code (springdoc); this document fixes contracts, rules and authorization before implementation.

## 1. Conventions
| Topic | Rule |
|---|---|
| Auth | Session cookie `HMSSESSION` (HttpOnly, Secure, SameSite=Lax). State-changing requests need header `X-XSRF-TOKEN` (value from `XSRF-TOKEN` cookie) |
| Org scoping | Derived from session principal. Never accepted in body/query. Cross-org ids → `404` |
| Content | `application/json; charset=utf-8`. camelCase. Instants ISO-8601 UTC (`2026-11-06T09:30:00Z`). Dates `YYYY-MM-DD`. Money = decimal **string** `"1250.00"` |
| IDs | Opaque strings (UUIDv7 server-side, ADR-0004); clients must not parse or validate their format. Patient-facing material uses business keys (UHID, invoice no.) |
| Pagination | `?page=0&size=20` (max 100), `sort=field,dir`. Response envelope `{ "items": [], "page": 0, "size": 20, "totalItems": 134 }` |
| Concurrency | Mutable resources return `version`; updates send `version`; mismatch → `409 CONCURRENT_MODIFICATION` |
| Idempotency | `Idempotency-Key: <uuid>` required on `POST /payments`, `/refunds`, `/invoices/{id}/issue`; same key+payload returns original response |
| Correlation | Request `X-Request-Id` accepted (1–64 chars of `A-Za-z0-9._-`; anything else is replaced) or generated; echoed in the response header, as `requestId` in every error, and as `correlationId` in logs |
| Errors | RFC 7807 (below) |
| Methods | POST create/commands; PATCH partial update; no DELETE for clinical/financial data (use status commands) |

### Error model
```json
{
  "type": "https://hms.example/problems/validation-error",
  "title": "Validation failed",
  "status": 422,
  "code": "VALIDATION_ERROR",
  "detail": "One or more fields are invalid.",
  "requestId": "6f1c9d0e-3a7b-4a39-9d5b-0c7b1b5a9e11",
  "errors": [ { "field": "dateOfBirth", "code": "NOT_IN_FUTURE", "message": "Date of birth cannot be in the future." } ]
}
```
| HTTP | code examples |
|---|---|
| 400 | MALFORMED_REQUEST |
| 401 | UNAUTHENTICATED, INVALID_CREDENTIALS, SESSION_EXPIRED |
| 403 | FORBIDDEN (missing permission), ACCOUNT_INACTIVE, PASSWORD_CHANGE_REQUIRED |
| 404 | NOT_FOUND (also for cross-org) |
| 409 | CONCURRENT_MODIFICATION, SLOT_CONFLICT, BED_NOT_AVAILABLE, PATIENT_ALREADY_ADMITTED, INVOICE_IMMUTABLE, EXCEEDS_OUTSTANDING, ENCOUNTER_FINALIZED, INVALID_STATE_TRANSITION, DUPLICATE_SUSPECTED |
| 422 | VALIDATION_ERROR, BUSINESS_RULE_VIOLATION |
| 423 | ACCOUNT_LOCKED |
| 429 | RATE_LIMITED |
| 500 | INTERNAL_ERROR (no internals leaked) |

Framework errors without a domain code use the status name (e.g. `405 METHOD_NOT_ALLOWED`, `415 UNSUPPORTED_MEDIA_TYPE`). `type` is `https://hms.example/problems/<code in kebab-case>`. Field errors never echo the rejected value. Implemented by `com.hms.shared.web.ProblemDetailsHandler`; shared exceptions: `NotFoundException` (404), `ConflictException(code)` (409), `BusinessRuleException([code])` (422).

Permission codes are listed per endpoint. `*` = see role mapping in PRD.

## 2. Authentication
| Method & path | Purpose | Permission |
|---|---|---|
| POST `/auth/login` | Create session | public (rate limited) |
| POST `/auth/logout` | End session | authenticated |
| GET `/auth/me` | Current user, org, permission list | authenticated |
| POST `/auth/change-password` | Change own password | authenticated |
| POST `/auth/mfa/verify` | Second factor (when enabled) | pre-auth session |

```http
POST /api/v1/auth/login
{ "email": "reception1@demo-clinic.example", "password": "••••••••" }
```
```json
200 { "user": { "id": "0192…", "fullName": "Demo Receptionist", "roles": ["RECEPTIONIST"], "mustChangePassword": false },
      "permissions": ["patient:read","patient:create","appointment:create"],
      "organization": { "id": "0192…", "name": "Demo Clinic", "timezone": "Asia/Kolkata" } }
```
Validation: email format; password 1–128 chars (policy applies on set, not login; a password over 72 UTF-8 bytes can never be correct and is an ordinary `401 INVALID_CREDENTIALS` that counts towards the lockout). Failure always `401 INVALID_CREDENTIALS` (identical body for unknown user / wrong password; unknown emails still run one BCrypt check so timing matches); lock after 5 failures for 15 min → `423 ACCOUNT_LOCKED`, also for the right password while locked; right password on an inactive account → `403 ACCOUNT_INACTIVE`. Login needs the CSRF header too: the SPA gets the `XSRF-TOKEN` cookie from any earlier response (e.g. `GET /auth/me`, even when it answers 401). The session id is rotated on every login. Rate limit (reverse proxy, `infra/proxy/nginx.conf`): per client IP, burst of 20 then 30/min; excess gets `429 RATE_LIMITED` Problem+JSON with `Retry-After: 5`. Sized for a clinic behind one NAT address; behind another load balancer the proxy must use the real client IP. `roles` (role codes) and `permissions` are loaded from the database at login and fixed for the session; a role change applies from the next login (ADR-0006). `organization` carries `id`, `name` and `timezone` (`[E2-1]`). Endpoints check permissions only (`hasAuthority('patient:read')`); an architecture test rejects any `@PreAuthorize` using `hasRole`/`hasAnyRole`. The catalogue is `com.hms.identity.api.Permissions` (database-design.md section 13).

`POST /auth/change-password` `{ "currentPassword", "newPassword" }` -> `204` (authenticated, CSRF header required). Policy (`com.hms.identity.domain.PasswordPolicy`, reused for admin-set temporary passwords): 12 characters to 72 UTF-8 bytes (BCrypt limit), not blank, not equal to the account email, no composition rules, no history; the new password must also differ from the current one. Violations are `422 PASSWORD_POLICY_VIOLATION` (rule names only); a wrong current password is `422 INVALID_CURRENT_PASSWORD` and counts towards the login lockout (a locked account gets `423 ACCOUNT_LOCKED`). Audited as `PASSWORD_CHANGED` (fields `passwordHash`, `mustChangePassword`) or `PASSWORD_CHANGE_FAILED`; passwords are never logged or audited. A successful change clears `must_change_password` and the lockout counters. While `must_change_password` is set, every `/api/**` call except `/auth/me`, `/auth/logout` and `/auth/change-password` returns `403 PASSWORD_CHANGE_REQUIRED` (checked against the database on each request, so it clears immediately); `/auth/me` reports the real `mustChangePassword`.

## 3. Users & roles
| Method & path | Purpose | Permission |
|---|---|---|
| GET `/users` ?status,role,q | List | `user:read` |
| POST `/users` | Create | `user:create` |
| GET `/users/{id}` | Detail | `user:read` |
| PATCH `/users/{id}` | Update name/roles | `user:update` |
| POST `/users/{id}/activate` · `/deactivate` | Lifecycle (deactivate kills sessions) | `user:update` |
| POST `/users/{id}/reset-password` | Admin reset → temp password, must change | `user:reset-password` |
| GET `/roles` · GET `/permissions` | Reference | `role:read` |
| POST `/roles` · PATCH `/roles/{id}` | Manage custom roles / permissions | `role:manage` |

```json
POST /users
{ "email": "dr.rao@demo-clinic.example", "fullName": "Dr. A. Rao", "roleCodes": ["DOCTOR"] }
→ 201 { "id": "…", "status": "ACTIVE", "mustChangePassword": true, "temporaryPassword": "shown-once", "version": 0 }
```
Rules: email unique across **all** organizations, case-insensitive (`409 DUPLICATE_EMAIL`), because login is email-only (owner decision 2026-11-12; an org selector would be needed before relaxing this for multi-tenant SaaS); cannot deactivate self (`409 CANNOT_DEACTIVATE_SELF`); cannot remove last active system administrator (`409 LAST_ADMINISTRATOR`). Temporary password is returned once (create and reset-password responses, `Cache-Control: no-store`) and never logged/audited; it is 16 characters, meets the password policy, and sets `mustChangePassword`.

Implementation notes (`[E1-7]`, `UserController`, `RoleController`):
- List `GET /users ?status=ACTIVE|INACTIVE &role=<roleCode> &q=<substring of name or email> &page &size` uses the section 1 envelope; detail and list rows carry `id, email, fullName, status, mustChangePassword, roleCodes, version, createdAt, updatedAt`. Detail is audited (`USER_VIEWED`), the list is not.
- `PATCH /users/{id}` `{ "version", "fullName"?, "roleCodes"? }`: `version` is required (`409 CONCURRENT_MODIFICATION` on mismatch); `roleCodes`, when present, replaces the whole set; unknown codes (including another organization's) are `422 UNKNOWN_ROLE`; at least one of the two fields is required.
- `activate` / `deactivate` are idempotent (no change, no audit when already in that state). Deactivation deletes all of the user's `spring_session` rows.
- `reset-password` returns the full user plus `temporaryPassword`, clears the lockout and ends the user's other sessions. A role change ends the user's sessions too (so it applies immediately instead of at next login); a user changing their own account keeps the current session. `POST /auth/change-password` also ends the user's other sessions and keeps the current one.
- "System administrator" is defined by permission, not role name: an active user whose roles together grant both `user:update` and `role:manage`. Any change (deactivation, role replacement, editing a role's permissions) that would take the count from at least one to zero is `409 LAST_ADMINISTRATOR`; such changes serialize on a lock of the organization row.
- Roles: `GET /roles` returns `[{ id, code, name, permissions[], version, createdAt, updatedAt }]` of the caller's organization; `GET /permissions` returns the catalogue as a JSON array of codes. `POST /roles` `{ code ^[A-Z][A-Z0-9_]{0,49}$, name, permissionCodes[] }` -> `201` (`409 DUPLICATE_ROLE_CODE`, `422 UNKNOWN_PERMISSION`); `PATCH /roles/{id}` `{ version, name?, permissionCodes? }` replaces the permission set and ends the sessions of every holder. Roles are never deleted and have no status.
- Audit actions (entity `APP_USER` / `ROLE`, field names only): `USER_CREATED`, `USER_UPDATED`, `USER_ACTIVATED`, `USER_DEACTIVATED`, `USER_PASSWORD_RESET`, `USER_VIEWED`, `ROLE_CREATED`, `ROLE_UPDATED`.

## 4. Organization, departments, specialties
| Method & path | Permission |
|---|---|
| GET `/organization` · PATCH `/organization` (name, timezone, address, gstin) | `org:read` · `org:update` |
| GET/POST `/departments`, PATCH `/departments/{id}` | `catalog:read` / `catalog:manage` |
| GET/POST `/specialties`, PATCH `/specialties/{id}` | `catalog:read` / `catalog:manage` |
| GET `/services`, GET `/services/{id}`, GET `/services/{id}/prices` | `catalog:read` |
| POST `/services`, PATCH `/services/{id}`, POST `/services/{id}/prices` | `catalog:manage` |
```json
POST /departments { "code": "CARDIO", "name": "Cardiology" } → 201 { "id":"…","code":"CARDIO","name":"Cardiology","active":true,"version":0 }
```
Rules: code `[A-Z0-9_]{2,20}`, unique per org. No delete; `active=false`. Timezone must be valid IANA id.

**Departments and specialties (`[E2-2]`)**: both lists have the same shape `{ id, code, name, active, version, createdAt, updatedAt }`. `GET /departments|/specialties` `?active=true|false&page=0&size=50` (size 1-100, ordered by code) returns the pagination envelope `{ items, page, size, totalItems }` for the caller's organization. `POST` `{ "code", "name" }`: code `[A-Z0-9_]{2,20}` (upper case only), name 1-100 chars; a duplicate code in the same organization is `409 DUPLICATE_DEPARTMENT_CODE` / `DUPLICATE_SPECIALTY_CODE` (another organization may use the same code). `PATCH /{id}` `{ "version", "name"?, "active"? }`: `version` required (`409 CONCURRENT_MODIFICATION` on mismatch), at least one of `name`/`active`, the code is immutable; `active=false` deactivates and `active=true` reactivates; another organization's id is `404`. There is no DELETE (`405`) and no database DELETE grant. Invalid input is `422 VALIDATION_ERROR`. Audit (entity `DEPARTMENT` / `SPECIALTY`, field names only): `*_CREATED`, `*_UPDATED`, `*_DEACTIVATED`, `*_ACTIVATED`. The default Facility Administrator role holds `catalog:*`.

**Service catalog and prices (`[E2-3]`, billing module)**: `GET /services` `?active=&page=0&size=50` (ordered by code, pagination envelope) and `GET /services/{id}` return `{ id, code, name, active, currentPrice, version, createdAt, updatedAt }`; `currentPrice` is the price valid now as a decimal string (`"500.00"`), `null` before the first price. `POST /services` `{ code [A-Z0-9_]{2,30}, name 1-150 }` -> `201` (`409 DUPLICATE_SERVICE_CODE`); `PATCH /services/{id}` `{ version, name?, active? }` as for departments (`409 CONCURRENT_MODIFICATION`); no DELETE. Prices are append-only versions: `GET /services/{id}/prices` (newest first, paged) returns `{ id, serviceId, amount, effectiveFrom, effectiveTo, createdAt }`; `POST /services/{id}/prices` `{ amount, effectiveFrom? }` -> `201`. `amount` is a decimal string with at most 2 decimals and >= 0 (JSON numbers or extra precision are `400`, negative is `422 VALIDATION_ERROR`); `effectiveFrom` defaults to now and may not be in the past (`422 PRICE_EFFECTIVE_IN_PAST`). A version is valid for `effectiveFrom <= t < effectiveTo` (half-open): the new version closes the previous open-ended one at its own start, so exactly one price applies at any instant. A new version must start after the latest version's start, otherwise `409 PRICE_RANGE_OVERLAP` (the database exclusion constraint backs this under concurrency, same code). A price is never edited or deleted; invoices copy the price, so a change never alters an invoice. Other modules read prices through `com.hms.billing.api.ServicePriceLookup#priceAt(organizationId, serviceId, instant)`. Audit: `SERVICE_CREATED|UPDATED|DEACTIVATED|ACTIVATED` (entity `SERVICE`) and `SERVICE_PRICE_CREATED` (entity `SERVICE_PRICE`, fields `serviceId, amount, effectiveFrom`; never values).

**Organization settings (`[E2-1]`)**: no id in the path, the organization is always the caller's (never from input).
`GET /organization` -> `{ id, name, timezone, gstin?, address?, version, createdAt, updatedAt }`. `PATCH /organization` `{ "version", "name"?, "timezone"?, "gstin"?, "address"? }`: `version` required (`409 CONCURRENT_MODIFICATION` on mismatch), at least one other field, omitted fields untouched, an empty `gstin`/`address` clears it. `name` 1-200 chars; `timezone` a region-based IANA id (`Asia/Kolkata`, `UTC`; offsets such as `+05:30` are rejected); `gstin` optional, 15 characters in the GSTIN format `^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$` (format only, the check digit is not verified); `address` up to 500 chars. Invalid input is `422 VALIDATION_ERROR`. Audit: `ORGANIZATION_UPDATED` (entity `ORGANIZATION`, field names only) and `ORGANIZATION_CREATED` (bootstrap).

**First-run bootstrap**: on startup, if no organization exists and `HMS_BOOTSTRAP_ORG_NAME` + `HMS_BOOTSTRAP_ADMIN_EMAIL` are set (`HMS_BOOTSTRAP_ORG_TIMEZONE` optional, default `Asia/Kolkata`), the app creates the organization, the default roles and a System Administrator (`mustChangePassword = true`) with a generated temporary password. The password is written only to the file named by `HMS_BOOTSTRAP_ADMIN_PASSWORD_FILE` (owner-only permissions where the OS supports them) and is never logged or audited; the variables without the file variable fail startup. A second start finds an organization and does nothing.

## 5. Doctors (practitioners)
| Method & path | Permission |
|---|---|
| GET `/doctors` ?specialtyId,departmentId,q | `practitioner:read` |
| POST `/doctors` | `practitioner:create` |
| GET/PATCH `/doctors/{id}` | read/update |
| PUT `/doctors/{id}/specialties` (replace set) | `practitioner:update` |
| PUT `/doctors/{id}/departments` | `practitioner:update` |
| PUT `/doctors/{id}/availability` (weekly template) | `practitioner:schedule` |
| POST `/doctors/{id}/availability-exceptions` | `practitioner:schedule` |
| GET `/doctors/{id}/slots?date=2026-11-10` (free slots) | `appointment:read` |
```json
POST /doctors
{ "userId": "…", "fullName": "Dr. A. Rao", "registrationNo": "TSMC-12345", "registrationCouncil": "TSMC",
  "qualifications": ["MBBS","MD"], "specialtyIds": ["…"], "primarySpecialtyId": "…", "departmentIds": ["…"], "defaultSlotMinutes": 15 }
→ 201 { "id":"…", "status":"ACTIVE", "version":0, … }
PUT /doctors/{id}/availability
{ "windows": [ { "weekday": 1, "start": "09:00", "end": "13:00", "slotMinutes": 15 } ] }
```
Rules: ≥1 specialty; exactly one primary; windows on same weekday may not overlap; end > start; slotMinutes 5–120. Staff: analogous `/staff` (`staff:*`).

## 6. Patients
| Method & path | Purpose | Permission |
|---|---|---|
| POST `/patients` | Register (dup check) | `patient:create` |
| GET `/patients` ?q,uhid,phone,dob | Search (fuzzy name) | `patient:search` |
| GET `/patients/{id}` | Detail (audited view) | `patient:read` |
| PATCH `/patients/{id}` | Update demographics | `patient:update` |
| POST `/patients/{id}/contacts` · PATCH/… | Emergency contacts | `patient:update` |
| POST `/patients/{id}/allergies` · PATCH `…/{aid}` | Allergies | `patient:update-clinical` (doctor/nurse) |
| POST `/patients/{id}/medical-history` | History entry | `patient:update-clinical` |
| GET `/patients/{id}/timeline` | Encounters + admissions history | `patient:read` + `encounter:read` |
| POST `/patients/duplicate-check` | Pre-register candidate lookup | `patient:create` |

```json
POST /patients
{ "fullName": "Test Patient One", "sex": "F", "dateOfBirth": "1988-04-12", "phone": "+919000000001",
  "address": { "line1": "1 Demo Street", "city": "Hyderabad", "state": "Telangana", "pincode": "500001" },
  "emergencyContact": { "name": "Test Contact", "relationship": "Spouse", "phone": "+919000000002" },
  "confirmNotDuplicate": false }
→ 201 { "id":"…", "uhid":"HMS-000124", "fullName":"Test Patient One", "status":"ACTIVE", "version":0 }
→ 409 { "code":"DUPLICATE_SUSPECTED", "candidates":[ { "id":"…","uhid":"HMS-000101","fullName":"Test Patient 1","dateOfBirth":"1988-04-12","phoneMasked":"+91*****0001","score":0.91 } ] }
```
**Implemented by `[E3-1]`: `POST /patients` and `GET /patients/{id}`** (search, update, contacts beyond the one emergency contact, allergies, timeline and duplicate check come later; `confirmNotDuplicate` is not read yet). `POST` returns `201` with the full patient: `{ id, uhid, fullName, sex, dateOfBirth, dobEstimated, phone, address, emergencyContact, status, version, createdAt, updatedAt }` (`address` and `emergencyContact` are `null` when not supplied; both are optional, but if given every field except `address.line2` is required). `uhid` is `HMS-` plus the next number of the organization's gapless sequence, at least 6 digits (`HMS-000124`); it is allocated under a row lock inside the registration transaction, so a failed registration never leaves a gap. Give exactly one of `dateOfBirth` or `ageYears` (0-130, requires `dobEstimated=true`; stored as an estimated date of birth); anything else is `422 VALIDATION_ERROR`. A bare 10-digit phone is stored as `+91...`. `GET /patients/{id}` is audited (`PATIENT_VIEWED`); another organization's id is `404 NOT_FOUND`. Audit: `PATIENT_CREATED` (entity `PATIENT`, field names such as `fullName`, `address.city`, never values) and `PATIENT_VIEWED`.

Rules: fullName 2–120; sex ∈ {M,F,O,U}; dateOfBirth ≤ today or `ageYears` with `dobEstimated=true`; phone E.164 (India default +91, 10 digits); pincode 6 digits. Retry with `confirmNotDuplicate=true` records an override. Search returns minimal fields (no history). `phone` search is exact; name is trigram (min 3 chars).

## 7. Appointments
| Method & path | Purpose | Permission |
|---|---|---|
| POST `/appointments` | Book | `appointment:create` |
| GET `/appointments` ?doctorId,patientId,date,status | List | `appointment:read` |
| GET `/appointments/{id}` | Detail | `appointment:read` |
| POST `/appointments/{id}/reschedule` | New time (links old) | `appointment:update` |
| POST `/appointments/{id}/cancel` | With reason | `appointment:cancel` |
| POST `/appointments/{id}/check-in` | → queue token | `appointment:check-in` |
| POST `/appointments/walk-in` | Create + check-in | `appointment:create` |
| POST `/appointments/{id}/no-show` | | `appointment:update` |
| GET `/queue?doctorId=&date=` | Live queue | `appointment:read` |
```json
POST /appointments { "patientId":"…","doctorId":"…","startAt":"2026-11-10T04:30:00Z","durationMinutes":15,"reason":"Fever for 2 days" }
→ 201 { "id":"…","status":"SCHEDULED","startAt":"…","endAt":"…","version":0 }
→ 409 { "code":"SLOT_CONFLICT", … }
POST /appointments/{id}/check-in → 200 { "status":"CHECKED_IN","tokenNo":7,"tokenDate":"2026-11-10" }
```
Rules: start in future (unless `appointment:backdate`); within availability unless `appointment:override`; duration multiple of slot; cancel needs `reason`; transitions per state machine else `409 INVALID_STATE_TRANSITION`.

## 8. Encounters & clinical
| Method & path | Purpose | Permission |
|---|---|---|
| POST `/encounters` | Start (from `appointmentId` or ad hoc) | `encounter:create` |
| GET `/encounters/{id}` | Detail (audited) | `encounter:read` |
| GET `/patients/{id}/encounters` | History | `encounter:read` |
| PUT `/encounters/{id}/observations` | Replace vitals set while OPEN | `observation:write` |
| PUT `/encounters/{id}/notes/{kind}` | Symptoms/exam/assessment while OPEN | `encounter:update` |
| PUT `/encounters/{id}/diagnoses` | While OPEN | `encounter:update` |
| PUT `/encounters/{id}/treatment-plan` | While OPEN | `encounter:update` |
| PUT `/encounters/{id}/follow-up` | While OPEN | `encounter:update` |
| POST `/encounters/{id}/finalize` | Lock | `encounter:finalize` (treating doctor) |
| POST `/encounters/{id}/addenda` | After finalize | `encounter:addendum` |
```json
POST /encounters { "appointmentId":"…","chiefComplaint":"Fever, cough" } → 201 { "id":"…","status":"OPEN","patient":{"id":"…","uhid":"HMS-000124"},"version":0 }
PUT /encounters/{id}/observations
{ "version":0, "observations":[ {"kind":"BP_SYS","value":"120","unit":"mmHg"}, {"kind":"TEMP_C","value":"38.4","unit":"C"} ] }
```
Rules: only the treating practitioner (or permission `encounter:update-any`) edits; vitals ranges validated as plausibility warnings (hard-limits reject impossible values like negative); edits to FINALIZED → `409 ENCOUNTER_FINALIZED`.

## 9. Prescriptions
| Method & path | Permission |
|---|---|
| POST `/encounters/{id}/prescriptions` (draft) | `prescription:create` |
| PUT `/prescriptions/{id}` (items, while DRAFT) | `prescription:create` |
| POST `/prescriptions/{id}/issue` | `prescription:issue` |
| POST `/prescriptions/{id}/revise` (creates new version, supersedes) | `prescription:issue` |
| GET `/prescriptions/{id}` · GET `/prescriptions/{id}/pdf` | `prescription:read` |
```json
PUT /prescriptions/{id}
{ "version":0, "items":[ { "drugName":"Paracetamol","strength":"500 mg","form":"Tablet","route":"Oral","dose":"1","frequency":"TID","durationDays":3,"instructions":"After food" } ] }
→ 200 { "allergyWarnings":[ { "substance":"Paracetamol","severity":"MILD" } ], … }
```
Rules: ≥1 item; drugName, dose, frequency, durationDays (1–365) mandatory; allergy match produces `allergyWarnings` (non-blocking; issuing requires `acknowledgeAllergyWarnings=true`).

## 10. Wards, beds, bed assignments
| Method & path | Permission |
|---|---|
| GET/POST `/wards`, PATCH `/wards/{id}` | `ward:read` / `ward:manage` |
| GET/POST `/wards/{id}/beds`, PATCH `/beds/{id}` (rate service, status) | `bed:read` / `bed:manage` |
| GET `/beds/availability?wardId=` | `bed:read` |
| GET `/admissions/{id}/bed-assignments` (history) | `admission:read` |
| POST `/admissions/{id}/bed-assignments/transfer` `{ "toBedId":"…","reason":"…" }` | `bed:assign` |
```json
POST /wards/{id}/beds { "bedNo":"G-12","dailyRateServiceId":"…" } → 201 { "id":"…","status":"ACTIVE" }
POST /admissions/{id}/bed-assignments/transfer { "toBedId":"…","reason":"Higher acuity" } → 200 { "currentAssignment": {…}, "previousAssignment": {…,"releasedAt":"…"} }
409 { "code":"BED_NOT_AVAILABLE" }
```
Rules: bed `OUT_OF_SERVICE` can't be assigned; cannot set a bed out of service while occupied; no limit on bed count.

## 11. Admissions & discharge
| Method & path | Permission |
|---|---|
| POST `/admissions` | `admission:create` |
| GET `/admissions` ?status,wardId,patientId | `admission:read` |
| GET `/admissions/{id}` | `admission:read` |
| PATCH `/admissions/{id}` (diagnosis) | `admission:update` |
| POST `/admissions/{id}/cancel` | `admission:cancel` |
| PUT `/admissions/{id}/discharge-summary` (draft) | `discharge:write` |
| POST `/admissions/{id}/discharge` | `discharge:complete` |
| GET `/admissions/{id}/charges-preview` (room-days + services) | `invoice:read` |
```json
POST /admissions { "patientId":"…","admittingDoctorId":"…","bedId":"…","admissionDiagnosis":"Acute gastroenteritis","sourceEncounterId":"…" }
→ 201 { "id":"…","admissionNo":"ADM-2026-0042","status":"ADMITTED","currentBed":{"id":"…","bedNo":"G-12","ward":"General"},"version":0 }
→ 409 PATIENT_ALREADY_ADMITTED | BED_NOT_AVAILABLE
POST /admissions/{id}/discharge { "version":3,"dischargedAt":"2026-11-12T07:00:00Z","invoiceId":"…","deferBilling":false }
→ 200 { "status":"DISCHARGED","bedReleased":true }
→ 422 BUSINESS_RULE_VIOLATION (no issued final invoice and deferBilling not permitted)
```
Rules: dischargedAt ≥ admittedAt and not in future (>5 min skew); summary required fields (final diagnosis, condition at discharge); `deferBilling` requires `discharge:defer-billing`.

## 12. Invoices & payments
| Method & path | Permission |
|---|---|
| POST `/invoices` (draft: OPD from `encounterId`, IPD from `admissionId`) | `invoice:create` |
| GET `/invoices` ?patientId,status,from,to | `invoice:read` |
| GET `/invoices/{id}` (items, payments, balance) | `invoice:read` |
| PUT `/invoices/{id}/items` (replace; DRAFT only) | `invoice:update` |
| POST `/invoices/{id}/issue` | `invoice:issue` |
| POST `/invoices/{id}/credit-notes` | `invoice:credit` |
| GET `/invoices/{id}/pdf` | `invoice:read` |
| POST `/invoices/{id}/payments` (or `POST /payments` with invoiceId) | `payment:create` |
| GET `/payments` ?from,to,mode | `payment:read` |
| GET `/payments/{id}/receipt` (PDF) | `payment:read` |
| POST `/payments/{id}/refunds` | `refund:create` (approval: `refund:approve`) |
| GET `/refunds` | `refund:read` |
```json
POST /invoices { "type":"OPD","patientId":"…","encounterId":"…" } → 201 { "id":"…","status":"DRAFT","total":"0.00" }
PUT /invoices/{id}/items
{ "version":0, "items":[ {"serviceId":"…","description":"Consultation","quantity":"1","unitPrice":"500.00","discount":"0.00","taxRate":"0.00"} ] }
→ 200 { "subtotal":"500.00","taxTotal":"0.00","total":"500.00" }
POST /invoices/{id}/issue (Idempotency-Key) → 200 { "status":"ISSUED","invoiceNo":"2026-27/000031","issuedAt":"…" }
POST /invoices/{id}/payments (Idempotency-Key)
{ "amount":"300.00","mode":"UPI","referenceNo":"TEST-UPI-0001" }
→ 201 { "id":"…","receiptNo":"RCT-2026-27/000044","outstanding":"200.00","invoiceStatus":"PARTIALLY_PAID" }
→ 409 { "code":"EXCEEDS_OUTSTANDING","detail":"Outstanding is 200.00." }
POST /payments/{id}/refunds { "amount":"100.00","reason":"Service not rendered","mode":"UPI" } → 201 { "refundNo":"…","status":"RECORDED" }
```
Rules: amounts are strings with ≤2 decimals, > 0; quantity 0.01–9999; unitPrice from catalog by default, override needs `invoice:price-override`; discount ≤ line amount; refund ≤ payment − refunds so far; refund > threshold needs `refund:approve` by a different user; issuing requires ≥ 1 item and total ≥ 0; DRAFT edits only; ISSUED edits → `409 INVOICE_IMMUTABLE`; cancel through credit note with reason.

## 13. Reports (read-only; all org-scoped, `from`/`to` in facility local dates, CSV via `Accept: text/csv`)
`GET /reports/registrations/daily`, `/reports/appointments`, `/reports/opd-consultations`, `/reports/admissions/active`, `/reports/beds/occupancy`, `/reports/discharges`, `/reports/revenue/daily`, `/reports/payments/outstanding` · Permissions: `report:clinical` (clinical/operational), `report:finance` (revenue/outstanding).

## 14. Audit (admin)
`GET /audit-events` ?actorId,entityType,entityId,patientId,action,from,to · `audit:read` (System Administrator, Facility Administrator). Read of audit events is itself audited.

**Implemented by `[E1-9]`**: `GET /audit-events` `?actorId=<uuid>&action=<UPPER_SNAKE>&entityType=<UPPER_SNAKE>&entityId=<uuid>&from=<UTC instant>&to=<UTC instant>&page=0&size=50` (size 1-100), permission `audit:read` (held by the default System Administrator; a Facility Administrator needs it granted via a custom role until the template is revised). All filters are optional and combine with AND; `from`/`to` are inclusive ISO-8601 UTC instants (`2026-01-31T00:00:00Z`) and `from` must not be after `to`. Malformed values, a reversed range or a bad page/size are `422 VALIDATION_ERROR`. Results are the section 1 envelope, newest first (`occurredAt`, then id, descending), rows `{ id, occurredAt, actorId, action, entityType, entityId, outcome, changedFields, correlationId }` (exactly what is stored: field names, never values; `actorId`/`entityId`/`correlationId` may be `null`). `audit_event` is outside RLS, so the query always carries an explicit `organization_id = <principal org>` predicate; the organization is never a parameter (an `organizationId` parameter is ignored) and another organization's events never appear. There is no path-id endpoint, so no cross-org 404 case applies. `patientId` is not supported yet (the table has no patient column; filter `entityType=PATIENT&entityId=<id>` for patient-entity events). Every successful query is audited as `AUDIT_VIEWED` (entity `AUDIT_EVENT`, no entity id) in the same transaction, with `changedFields` holding the names of the filters used (`action`, `entityId`, `from`...), never their values; rejected requests write nothing. The time range is on the partition key `occurred_at` (partition pruning); ordering uses `ix_audit_event_org_time`. UI: `/audit` (nav entry "Audit", route guarded by `audit:read`) with the six filters and a paged table.

## 15. Cross-cutting API tests (required)
Every endpoint: (1) 401 unauthenticated, (2) 403 without permission, (3) 404 cross-org, (4) validation cases, (5) audit event written for audited actions. Enforced by a reflection test that fails when a controller method lacks `@PreAuthorize`.
