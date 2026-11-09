# HMS — High-Level Architecture (v0.2, draft)

## 1. C4 Level 1 — System context
```mermaid
C4Context
  title HMS - System Context
  Person(staff, "Facility staff", "Receptionist, nurse, doctor, accountant, admins")
  System(hms, "Hospital Management System", "OPD, basic IPD, billing, reporting")
  System_Ext(abdm, "ABDM gateway (future)", "ABHA, consent, health record exchange")
  System_Ext(smtp, "Email/SMS provider (future)", "Notifications")
  Rel(staff, hms, "Uses", "HTTPS, browser")
  Rel(hms, abdm, "FHIR R4 / ABDM APIs", "Stage 1.5+, not in R1")
  Rel(hms, smtp, "Sends notifications", "Not in R1")
```

## 2. C4 Level 2 — Containers
```mermaid
C4Container
  title HMS - Containers (Release 1)
  Person(staff, "Staff")
  Container_Boundary(host, "Single host / VM (Docker Compose)") {
    Container(proxy, "Reverse proxy", "Caddy or Nginx", "TLS termination, static SPA, /api routing, rate limit")
    Container(spa, "Web SPA", "React + TypeScript", "Static assets served by proxy")
    Container(api, "HMS API", "Java 21, Spring Boot", "Modular monolith, REST /api/v1")
    ContainerDb(db, "PostgreSQL 17", "RDBMS", "Single DB, schema hms, Flyway-managed; roles hms_owner (migrations) and hms_app (runtime)")
    Container(prom, "Prometheus + Grafana", "Monitoring", "Metrics, dashboards, alerts")
    Container(backup, "Backup agent", "pgBackRest or managed PITR", "Base backup + WAL archive, encrypted off-host copy")
  }
  Rel(staff, proxy, "HTTPS")
  Rel(proxy, spa, "serves")
  Rel(proxy, api, "HTTP /api")
  Rel(api, db, "JDBC (pool)")
  Rel(prom, api, "scrapes /actuator/prometheus")
  Rel(backup, db, "WAL/base")
```
Rationale for scale: 50 concurrent users fit comfortably on one app instance + one PG. No Redis (sessions in PostgreSQL via Spring Session JDBC), no queue (in-process events), no object store in R1 (PDFs rendered on demand, not stored; attachments out of scope). Introduce each only when a requirement forces it.

## 3. C4 Level 3 — API components (modules)
```mermaid
flowchart TB
  subgraph API["HMS API (Spring Boot, one deployable)"]
    direction TB
    subgraph web["Web layer (per module controllers)"]
      C1[REST controllers + DTOs]
      SEC[Security filter chain / permission checks]
      ERR[Problem+JSON exception mapping]
    end
    subgraph mods["Business modules (each: api / application / domain / infrastructure)"]
      ORG[organization]
      IDN[identity]
      PRC[practitioner]
      PAT[patient]
      APT[appointment]
      CLN[clinical]
      ADM[admission]
      BED[bedmanagement]
      BIL[billing]
      PAY[payment]
      AUD[audit]
      RPT[reporting]
    end
    SHARED[shared-kernel: ids, money, time, errors, tenant context]
  end
  C1 --> mods
  mods --> SHARED
  mods --> AUD
```

## 4. Module dependency diagram (allowed direction: arrow = "may call the public API of")
```mermaid
flowchart LR
  AUD[audit]
  ORG[organization]
  IDN[identity] --> ORG
  PRC[practitioner] --> ORG
  PRC --> IDN
  PAT[patient] --> ORG
  APT[appointment] --> PAT
  APT --> PRC
  CLN[clinical] --> PAT
  CLN --> PRC
  CLN --> APT
  BED[bedmanagement] --> ORG
  ADM[admission] --> PAT
  ADM --> PRC
  ADM --> BED
  ADM --> CLN
  BIL[billing] --> PAT
  BIL --> ORG
  ADM -.implements ChargeSource port of.-> BIL
  PAY[payment] --> BIL
  RPT[reporting] -.read-only SQL views.-> DB[(PostgreSQL)]
```
All modules may publish audit events through `audit`'s public `AuditRecorder` interface (omitted arrows for readability). Billing does **not** depend on admission/clinical: it reacts to events (`PatientDischarged`, `EncounterFinalized`) and receives charge lines via its own API. Billing owns a small `ChargeSource` port; admission implements it to supply room-day charges (dependency inversion — the dotted arrow means admission depends on billing's `api` package, never the reverse). Reporting reads SQL views, not other modules' repositories; views are owned and versioned by reporting (documented exception to the "no arbitrary access" rule, ADR-0001). Boundary enforcement: ArchUnit rules in the `architectureTest` source set, run in CI (ADR-0001, accepted; Spring Modulith deferred).

## 5. Layering inside a module
```
module/
  api/             public interfaces + DTOs usable by other modules (the ONLY package others may import)
  application/     use-case services (@Transactional boundary), command/query handlers
  domain/          entities, value objects, domain services, state machines, events
  infrastructure/  JPA repositories, adapters
  web/             REST controllers, request/response DTOs (internal)
```
Transaction boundary = application service method. Controllers never open transactions. Cross-module calls inside one use case participate in the caller's transaction (monolith benefit) but go through `api` interfaces.

## 6. Request flow
```mermaid
sequenceDiagram
  participant B as Browser SPA
  participant P as Proxy
  participant F as Filter chain
  participant C as Controller
  participant S as Application service
  participant D as PostgreSQL
  B->>P: HTTPS POST /api/v1/patients (cookie + CSRF header)
  P->>F: forward (X-Request-Id)
  F->>F: correlation id (MDC), session lookup, CSRF check
  F->>F: authenticate -> principal(userId, orgId, permissions)
  F->>C: authorised by @PreAuthorize("hasAuthority('patient:create')")
  C->>S: command (validated via Bean Validation)
  S->>D: BEGIN, SET LOCAL app.organization_id and app.user_id
  S->>D: domain work + INSERT audit_event
  S->>D: COMMIT
  S-->>C: result
  C-->>B: 201 JSON (or application/problem+json)
```

## 7. Authentication flow (proposed: server-side session)
```mermaid
sequenceDiagram
  participant B as Browser
  participant A as Auth endpoint
  participant U as identity module
  participant D as DB (users, spring_session)
  B->>A: POST /api/v1/auth/login {email,password}
  A->>U: authenticate (BCrypt/Argon2, lockout check)
  U->>D: load user, verify hash, update counters
  alt success
    U->>D: create session (rotate id on login)
    A-->>B: 200 + Set-Cookie HMSSESSION (HttpOnly,Secure,SameSite=Lax) + XSRF-TOKEN cookie
    U->>D: audit LOGIN_SUCCESS
  else failure
    U->>D: failed_attempts++, lock when threshold, audit LOGIN_FAILED
    A-->>B: 401 generic message (no user enumeration)
  end
  B->>A: POST /api/v1/auth/logout
  A->>D: delete session, audit LOGOUT
```
Idle timeout 30 min (configurable), absolute 12 h. Admin deactivation deletes the user's sessions immediately. See ADR-0005 for the deviation from the charter's JWT proposal.

## 8. OPD flow
```mermaid
sequenceDiagram
  participant R as Receptionist
  participant N as Nurse
  participant Dr as Doctor
  participant AC as Accountant/Reception
  R->>R: Search patient (dup check) / register
  R->>R: Book or walk-in appointment
  R->>R: Check-in -> queue token
  N->>N: Record observations (vitals)
  Dr->>Dr: Open next in queue -> start Encounter
  Dr->>Dr: Symptoms, notes, diagnosis, treatment plan, prescription
  Dr->>Dr: Finalize encounter (locks), print prescription
  AC->>AC: Create invoice (suggested consultation item) -> issue
  AC->>AC: Record payment -> receipt
```

## 9. IPD flow
```mermaid
sequenceDiagram
  participant Dr as Doctor
  participant R as Reception
  participant BM as BedManagement
  participant AD as Admission
  participant BL as Billing
  Dr->>R: Decide admission
  R->>AD: Create admission (patient, doctor, diagnosis)
  AD->>BM: allocate bed (FOR UPDATE + unique index)
  BM-->>AD: assignment or 409
  Note over AD,BM: transfers: release + assign in one tx
  Dr->>AD: Initiate discharge, author discharge summary
  AD->>BL: request final invoice draft (room-days from assignment history + services)
  BL-->>AD: draft invoice
  R->>BL: review, issue invoice
  R->>AD: Complete discharge (release bed, status DISCHARGED)
```

## 10. Billing flow
```mermaid
flowchart LR
  A[Draft invoice<br/>items editable] -->|issue: lock sequence,<br/>assign number, freeze totals| B[ISSUED]
  B -->|payment: lock invoice,<br/>check outstanding| C[PARTIALLY_PAID / PAID]
  C -->|refund: reference payment| D[Refund record]
  B -->|error found| E[Credit note] --> F[New draft invoice]
  B --> G[Audit event in same tx at every step]
```

## 11. Deployment diagram
```mermaid
flowchart TB
  subgraph Internet
    U[Staff browsers]
  end
  subgraph IndiaRegion["Cloud VM, India region"]
    subgraph Compose["Docker Compose"]
      PX[caddy/nginx :443]
      API[hms-api container]
      PG[(postgres container or managed PG)]
      PM[prometheus]
      GF[grafana]
    end
    VOL[(Encrypted volume)]
  end
  OFF[(Off-host encrypted backup bucket, India region)]
  U --> PX --> API --> PG
  PM --> API
  GF --> PM
  PG --- VOL
  PG -- WAL + base backup --> OFF
```
Environments: local compose → staging (synthetic data only) → production. CI builds an image tagged by git SHA; staging auto-deploys; production is a manual promote. Rollback = redeploy previous image; DB migrations are forward-only and written **expand/contract** compatible so the previous app version works with the new schema for at least one release.

## 12. Cross-cutting concerns
| Concern | Approach |
|---|---|
| Errors | RFC 7807 `application/problem+json` with stable `code` |
| Observability | JSON logs with `correlationId`, `userId`, `orgId`; no PHI in logs (log filter + tests); Micrometer → Prometheus; health: liveness/readiness; per-module attribution, dashboards and alerts per ADR-0014 |
| Configuration | 12-factor env vars; secrets via env from secret store/docker secrets; never in repo |
| Time | `Clock` injected; all `Instant`; facility TZ (Asia/Kolkata constant in R1, database-design.md D2) only for day and financial-year boundaries |
| Money | `Money` value object over `BigDecimal` (scale 2) |
| API docs | springdoc-openapi; spec checked in CI for breaking changes |
| Idempotency | `Idempotency-Key` header on payment/refund/invoice-issue POSTs |
