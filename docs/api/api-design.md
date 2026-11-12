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
Validation: email format; password 1–128 chars (policy applies on set, not login). Failure always `401 INVALID_CREDENTIALS` (identical body for unknown user / wrong password; unknown emails still run one BCrypt check so timing matches); lock after 5 failures for 15 min → `423 ACCOUNT_LOCKED`, also for the right password while locked; right password on an inactive account → `403 ACCOUNT_INACTIVE`. Login needs the CSRF header too: the SPA gets the `XSRF-TOKEN` cookie from any earlier response (e.g. `GET /auth/me`, even when it answers 401). The session id is rotated on every login. Rate limit (reverse proxy, `infra/proxy/nginx.conf`): per client IP, burst of 20 then 30/min; excess gets `429 RATE_LIMITED` Problem+JSON with `Retry-After: 5`. Sized for a clinic behind one NAT address; behind another load balancer the proxy must use the real client IP. `roles` (role codes) and `permissions` are loaded from the database at login and fixed for the session; a role change applies from the next login (ADR-0006). Until `[E2-1]` lands, `organization` carries only `id`. Endpoints check permissions only (`hasAuthority('patient:read')`); an architecture test rejects any `@PreAuthorize` using `hasRole`/`hasAnyRole`. The catalogue is `com.hms.identity.api.Permissions` (database-design.md section 13).

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
Rules: email unique across **all** organizations, case-insensitive (`409 DUPLICATE_EMAIL`), because login is email-only (owner decision 2026-11-12; an org selector would be needed before relaxing this for multi-tenant SaaS); cannot deactivate self; cannot remove last active system administrator. Temporary password is returned once and never logged/audited.

## 4. Organization, departments, specialties
| Method & path | Permission |
|---|---|
| GET `/organization` · PATCH `/organization` (name, timezone, address, gstin) | `org:read` · `org:update` |
| GET/POST `/departments`, PATCH `/departments/{id}` | `catalog:read` / `catalog:manage` |
| GET/POST `/specialties`, PATCH `/specialties/{id}` | `catalog:read` / `catalog:manage` |
```json
POST /departments { "code": "CARDIO", "name": "Cardiology" } → 201 { "id":"…","code":"CARDIO","name":"Cardiology","active":true,"version":0 }
```
Rules: code `[A-Z0-9_]{2,20}`, unique per org. No delete; `active=false`. Timezone must be valid IANA id.

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

## 15. Cross-cutting API tests (required)
Every endpoint: (1) 401 unauthenticated, (2) 403 without permission, (3) 404 cross-org, (4) validation cases, (5) audit event written for audited actions. Enforced by a reflection test that fails when a controller method lacks `@PreAuthorize`.
