# HMS Release 1 — Product Requirements Document (v0.2, draft for review)

Status: Draft · Supersedes the scope tables in `00-charter-and-architecture.md` where they conflict (see `00-review-notes.md`).
Legend: **[C]** confirmed decision · **[P]** proposed recommendation · **[A]** assumption · **[Q]** open question.

## 1. Business objectives
| # | Objective | Measure |
|---|---|---|
| BO1 | Replace paper/Excel for a small multi-specialty clinic / nursing home end to end (register → consult → admit → bill → discharge) | One pilot facility runs daily operations on HMS |
| BO2 | Financial integrity: no silent change to money records | 0 unaudited mutations of issued invoices/payments |
| BO3 | Patient safety & confidentiality: one patient identity, traceable access to records | 100% of clinical reads/writes audited |
| BO4 | Foundations for hospital (Stage 2) and SaaS (Stage 3) without rewrite | Organization ownership on all business rows; module boundaries enforced by tests |
| BO5 | Operable by one developer | Single deployable, one DB, runbooks exist and have been exercised |

## 2. Scope
**In (Release 1) [C]:** IAM, patients, practitioners/staff, appointments, OPD, basic IPD (wards, beds, admission, transfer, discharge), billing & payments, reporting, audit.
**Out:** see10.
**One facility, one organization** at launch. Schema carries `organization_id` everywhere [C]; `facility_id` is deferred to Stage 3 [P] (a single `facility` row per organization holds timezone/address; business rows reference organization only — avoids a second ownership axis before it is needed).

## 3. Personas
| Persona | Goals | Key permissions |
|---|---|---|
| System Administrator | Provision organization, users, roles; no clinical access by default | `user:*`, `role:*`, `org:*`, `audit:read` |
| Facility Administrator | Run facility config: departments, wards, beds, service catalog, schedules | `catalog:*`, `ward:*`, `bed:*`, `practitioner:*`, `report:read` |
| Doctor | Consult, document, prescribe, admit/discharge | `patient:read`, `encounter:*`, `prescription:*`, `admission:*`, `discharge:*` |
| Nurse | Vitals/observations, bed management support, IPD care notes (basic) | `patient:read`, `observation:write`, `bed:assign`, `admission:read` |
| Receptionist | Registration, appointments, check-in, admission desk, collect payments | `patient:*`, `appointment:*`, `admission:create`, `payment:create` |
| Accountant | Invoicing, payments, refunds, revenue reports | `invoice:*`, `payment:*`, `refund:*`, `report:finance` |
Roles are data (role → permission rows). Code checks permissions only [C: "permissions can evolve"].

## 4. Functional requirements
Priority: M = must for Release 1, S = should, C = could.

### 4.1 Identity & Access (IAM)
| ID | Requirement | Pri |
|---|---|---|
| IAM-1 | Login/logout with username (email) + password | M |
| IAM-2 | RBAC with permission-level checks; role↔permission editable by admin | M |
| IAM-3 | Create user, assign roles, activate/deactivate (no delete) | M |
| IAM-4 | Password policy, change password, admin-initiated reset, forced change on first login | M |
| IAM-5 | Account lockout after N failed attempts; session idle & absolute timeout; admin can revoke sessions | M |
| IAM-6 | Audit of auth events and permission changes | M |
| IAM-7 | MFA (TOTP) for admin/doctor roles | S [P] |

### 4.2 Patient
| ID | Requirement | Pri |
|---|---|---|
| PAT-1 | Register patient: name, sex, DOB (or estimated age), phone, address, photo-less; generates UHID (facility-readable ID) + UUID | M |
| PAT-2 | Contacts incl. emergency contact (name, relation, phone) | M |
| PAT-3 | Medical history entries and allergies (substance, reaction, severity, status) | M |
| PAT-4 | Search by name (fuzzy), phone, UHID, ABHA number (stored, unverified in R1) | M |
| PAT-5 | Duplicate detection at registration: warn on likely matches (same DOB + similar name, same phone); user must explicitly confirm "not a duplicate" (recorded) | M |
| PAT-6 | Update demographics; every change audited with old/new metadata (no sensitive values in log text) | M |
| PAT-7 | Encounter & admission history view | M |
| PAT-8 | Merge duplicate patients | C (Stage 2; design leaves `merged_into_patient_id`) |

### 4.3 Practitioners & staff
| ID | Requirement | Pri |
|---|---|---|
| PRC-1 | Register doctor (registration/licence number, qualifications), link to user | M |
| PRC-2 | Multiple specialties per doctor; multiple doctors per specialty | M |
| PRC-3 | Department assignment (many-to-many) | M |
| PRC-4 | Weekly availability templates + date-specific exceptions (leave) | M |
| PRC-5 | Staff records (nurse, receptionist, accountant…) with role assignment via user | M |

### 4.4 Appointments
| ID | Requirement | Pri |
|---|---|---|
| APT-1 | Book appointment in a free slot of a practitioner; no overlapping active appointments per practitioner (DB-enforced) | M |
| APT-2 | Reschedule (keeps history as a link to the original) and cancel with reason | M |
| APT-3 | Status: `SCHEDULED → CHECKED_IN → IN_CONSULTATION → COMPLETED`; `CANCELLED`, `NO_SHOW` | M |
| APT-4 | Check-in puts patient in the practitioner's consultation queue (token number per practitioner per day) | M |
| APT-5 | Walk-in: create appointment of type `WALK_IN` directly in `CHECKED_IN` | M |
| APT-6 | Queue view for doctor and front desk | M |
| APT-7 | SMS/WhatsApp reminders | C (out R1) |

### 4.5 OPD / Clinical
| ID | Requirement | Pri |
|---|---|---|
| OPD-1 | Start encounter from an appointment (or ad hoc), one OPD encounter per consultation | M |
| OPD-2 | Chief complaint/symptoms, observations (vitals: BP, pulse, temp, SpO₂, weight, height, RR), clinical notes | M |
| OPD-3 | Diagnoses (free text + optional ICD-10 code); primary/secondary | M |
| OPD-4 | Treatment plan; follow-up recommendation (date/interval, instructions) | M |
| OPD-5 | Prescription with items (drug name, strength, form, dose, frequency, duration, route, instructions); allergy warning shown against recorded allergies [P: name-match only in R1] | M |
| OPD-6 | Finalizing an encounter locks it; later changes are **addenda** (new notes referencing the original), not edits | M |
| OPD-7 | Printable prescription | M |
| OPD-8 | Drug master & ICD-10 catalog | S (R1 uses free text + small seed list; full masters Stage 2) |

### 4.6 IPD (basic)
| ID | Requirement | Pri |
|---|---|---|
| IPD-1 | Configure wards and beds (type/class, daily rate reference, status `ACTIVE/OUT_OF_SERVICE`); no application limit on count | M |
| IPD-2 | Admit patient: admitting doctor, admission diagnosis, source (OPD encounter/direct), expected stay | M |
| IPD-3 | A patient may have at most one active admission | M |
| IPD-4 | Allocate bed at admission; a bed has at most one active assignment (DB-enforced) | M |
| IPD-5 | Transfer bed: closes current assignment and opens new one in one transaction; history preserved | M |
| IPD-6 | Bed availability view per ward; occupancy | M |
| IPD-7 | Daily accommodation charge posted per bed-day (see BR-B6) | M |
| IPD-8 | Discharge: discharge summary (diagnosis, course, procedures, condition, medications, follow-up), release bed, close admission | M |
| IPD-9 | Final bill generated at discharge; discharge blocked from `DISCHARGED` until invoice is issued or explicitly deferred by permission holder [P] | M |
| IPD-10 | Nursing notes, MAR, OT, ICU | Out |

### 4.7 Billing & payments
| ID | Requirement | Pri |
|---|---|---|
| BIL-1 | Service catalog with price (versioned: price changes never alter issued invoices) | M |
| BIL-2 | OPD invoice (consultation + services) linked to encounter/appointment | M |
| BIL-3 | IPD invoice: room charges + services; interim and final | M |
| BIL-4 | Invoice lifecycle `DRAFT → ISSUED → (PARTIALLY_PAID/PAID) ; ISSUED → CANCELLED/VOIDED` via credit note; items editable only in DRAFT | M |
| BIL-5 | Payments: cash/UPI/card/bank (manually recorded), reference number, partial payments, multiple per invoice | M |
| BIL-6 | Outstanding balance computed = total − payments + refunds | M |
| BIL-7 | Refund records referencing a payment (and optionally credit note); approval permission | M |
| BIL-8 | Printable invoice and receipt (PDF) | M |
| BIL-9 | Discounts (amount/percent per item or invoice with reason & permission) | S |
| BIL-10 | GST: line-level tax rate/amount captured; most clinical services are GST-exempt — **legal/accounting review required** | S [Q] |
| BIL-11 | Online payment gateway, insurance/TPA | Out |

### 4.8 Reporting (all org-scoped, date-range filter, CSV export)
Daily registrations · appointments (by status/doctor) · OPD consultations · active admissions · bed occupancy · discharges · daily revenue (by mode/service) · outstanding payments.

### 4.9 Audit
| ID | Requirement | Pri |
|---|---|---|
| AUD-1 | Append-only `audit_event` for: auth events, permission/role changes, patient create/update/**view**, clinical record create/finalize/view, admission/bed events, invoice/payment/refund events | M |
| AUD-2 | Fields: actor, action, entity type/id, timestamp, change metadata (field names, not sensitive values), correlation id, outcome | M |
| AUD-3 | Audit viewer with filters for admin | S |
| AUD-4 | No passwords/tokens/PHI payloads in logs or audit metadata | M |

## 5. Non-functional requirements (targets, to be **validated**, not assumed)
| Area | Target | Validation |
|---|---|---|
| Concurrency | 50 concurrent users | k6/Gatling load test with realistic mix |
| Latency | p95 < 500 ms ordinary reads [C] | Load test in CI nightly on staging |
| Availability | 99.5% monthly (~3.6 h/month) | Uptime monitor; single-node means planned maintenance must be short |
| Integrity | ACID; invariants enforced in DB where possible | Concurrency tests (bed, appointment, payment) |
| Security | AuthN/Z, TLS, encryption at rest, secrets managed, OWASP ASVS L2 as goal | Security tests; external review before go-live |
| Backup | Automated daily base backup + WAL archiving | RPO 15 min, RTO 4 h [P]; **restore drill before go-live** |
| Maintainability | Module boundaries enforced by automated test (ArchUnit, ADR-0001) | CI |
| Time | Store UTC; org timezone configurable (default `Asia/Kolkata`) | Unit tests around day boundaries (reports, daily charges) |
| Accessibility/UX | Usable on desktop browsers & tablets; keyboard-first front desk | UAT |

## 6. User journeys (summaries; detail in `user-journeys.md` later)
1. **Walk-in OPD:** Receptionist searches patient → (dup check) registers → walk-in appointment → token → nurse records vitals → doctor consults, diagnoses, prescribes → doctor finalizes → receptionist/accountant issues invoice → payment → receipt.
2. **Booked appointment:** Book slot → reminder (out) → check-in → queue → as above.
3. **Admission:** Doctor decides to admit → receptionist creates admission with bed → daily charges accrue → bed transfer → doctor writes discharge summary → final invoice → payments → discharge → bed released.
4. **Correction of a billing error:** Accountant cancels issued invoice via credit note with reason → new invoice drafted → audit shows full chain.

## 7. Business rules
| ID | Rule |
|---|---|
| BR-P1 | One Patient identity per person per organization. Visits/admissions/invoices attach to the existing patient. |
| BR-P2 | UHID is unique per organization, never reused. |
| BR-P3 | Possible duplicates (same DOB + trigram name similarity ≥ threshold, or same normalized phone + similar name) require explicit confirmation; the confirmation is audited. |
| BR-P4 | Patients are never hard-deleted; status `ACTIVE/INACTIVE/MERGED/DECEASED`. |
| BR-A1 | A practitioner cannot have two overlapping active appointments (DB exclusion constraint). |
| BR-A2 | Appointment must fall within practitioner availability unless overridden by `appointment:override` permission (audited). |
| BR-A3 | Valid status transitions only (state machine in domain layer). |
| BR-C1 | Encounter belongs to exactly one patient; may link to one appointment. |
| BR-C2 | Finalized encounter, notes, diagnoses and prescriptions are immutable; amendments are addenda with author/time. |
| BR-C3 | Prescription items require drug name, dose, frequency, duration. |
| BR-I1 | At most one active admission per patient. |
| BR-I2 | At most one open bed assignment per bed and per admission. |
| BR-I3 | Beds `OUT_OF_SERVICE` or occupied cannot be assigned. |
| BR-I4 | Discharge closes the open bed assignment and releases the bed in the same transaction. |
| BR-I5 | Admission, encounter and bed history are never deleted. |
| BR-B1 | Money is `NUMERIC(14,2)` INR; rounding rule: half-up at line level, documented in ADR-0012. |
| BR-B2 | Invoice number is gapless and sequential per organization per financial year (Apr–Mar), assigned at issue, not draft. |
| BR-B3 | Issued invoices are immutable; correction only by credit note / cancel-and-reissue. |
| BR-B4 | Payments are append-only; reversal via refund/reversal record. |
| BR-B5 | Payment cannot exceed outstanding balance (overpayment is rejected; advances are Stage 2). |
| BR-B6 | Accommodation charge = bed rate × bed-days. A bed-day is counted per calendar day (facility TZ) on which the patient holds a bed at the **checkout-time rule**: [A] charge per midnight occupied, with day of admission charged and day of discharge not charged unless admitted/discharged same day (minimum 1 day). Rule is configurable per org **— confirm with pilot facility [Q]**. Transfers: each day charged at the rate of the bed held at midnight. |
| BR-B7 | Each financial mutation has an audit event written in the same DB transaction. |
| BR-O1 | Every business record carries `organization_id`; every query is scoped by the caller's organization. |

## 8. Acceptance criteria (representative; full criteria live on each backlog issue)
| Req | Given / When / Then |
|---|---|
| IPD-4 | Given bed B free, when two admissions concurrently allocate B, then exactly one succeeds and the other receives `409 BED_NOT_AVAILABLE`; DB has one open assignment. |
| APT-1 | Given Dr X has 10:00–10:15 booked, when another booking overlaps, then `409 SLOT_CONFLICT`. |
| BIL-4 | Given an ISSUED invoice, when item edit is attempted, then `409 INVOICE_IMMUTABLE`; no row changes. |
| BIL-5 | Given outstanding 1000.00, when two concurrent payments of 600.00 arrive, then one succeeds, other `409 EXCEEDS_OUTSTANDING`; balance never negative. |
| PAT-5 | Given an existing patient with same DOB and similar name, when registering, then response lists candidates and registration requires `confirmNotDuplicate=true`. |
| AUD-1 | Given a doctor opens a patient record, then an audit event `PATIENT_VIEWED` exists with actor and correlation id and no clinical content. |
| BR-O1 | Given user of org A, when requesting org B's patient by id, then `404`. |

## 9. Assumptions
| ID | Assumption | Impact if wrong |
|---|---|---|
| A1 | Pilot facility has reliable broadband (online-only app) | Offline mode is a large epic |
| A2 | UI in English; i18n-ready strings | Telugu/Hindi print templates add effort |
| A3 | Single timezone per org (`Asia/Kolkata`) | — |
| A4 | Free-text drugs/diagnoses acceptable in R1 | Drug master moves earlier |
| A5 | No photo/document attachments in R1 | Needs object storage earlier |
| A6 | No insurance/TPA; self-pay only | Major billing extension |
| A7 | Payments recorded manually (no gateway) | — |
| A8 | Self-hosted/managed VM deployment in an India region | Cloud choice affects backup tooling |
| A9 | Pilot clinic availability is not guaranteed | Requirements validation risk |

## 10. Out of scope (Release 1)
Laboratory, pharmacy/inventory/procurement, emergency department, OT, nursing MAR/care plans, insurance/TPA, online payments, patient portal/app, telemedicine, SMS/WhatsApp, multi-facility, multi-tenant onboarding/subscription, **live ABDM integration** (R1 only stores ABHA number and designs FHIR mapping boundary), offline mode, patient merge UI, e-signature of documents.

## 11. Compliance posture (requires legal review — no compliance is claimed)
DPDP Act 2023 / Rules 2025 and ABDM are design references. R1 builds: purpose-tagged audit of access, role-based least privilege, encryption in transit/at rest, retention fields and soft delete, data-correction workflow (patient update with audit), breach-response runbook. Needs counsel: lawful basis for health data processing by clinic as fiduciary/processor, consent notice wording, retention periods (state clinical-establishment rules, medico-legal), commencement dates, ABDM certification path. See charter7 for dates reported there (unverified in this session).

## 12. Risks (top; full register in `project-management/risk-register.md`)
| ID | Risk | L | I | Mitigation |
|---|---|---|---|---|
| R1 | Solo bandwidth alongside full-time role | H | H | Weekly time budget, thin slices, scope cut order defined in roadmap |
| R2 | R1 scope (OPD+IPD+billing) larger than charter's OPD-only MVP | H | H | Milestone gating; IPD after OPD billing is stable |
| R3 | Billing/GST misinterpretation | M | H | Accountant review, keep GST fields optional |
| R4 | Clinical safety (wrong patient) | M | H | Dup detection, patient banner on every screen, immutable records |
| R5 | PHI breach | M | H | Least privilege, audit, pen test pre-go-live |
| R6 | Regulatory misread | M | H | Counsel review before pilot with real data |
| R7 | No design partner to validate workflows | M | H | Secure pilot before M3 |
| R8 | Backup never restore-tested | M | H | Restore drill is a go-live gate |
