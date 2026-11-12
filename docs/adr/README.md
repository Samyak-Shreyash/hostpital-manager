# Architecture Decision Records

**Accepted (baseline)** = confirmed in the project brief. **Accepted** = approved by the owner (all of 0001–0015 as of 2026-11-12). New ADRs start as **Proposed** and are not binding until approved.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-modular-monolith.md) | Modular monolith (vs microservices) | Accepted (ArchUnit only; Spring Modulith deferred) |
| [0002](0002-data-access-spring-data-jpa-hibernate.md) | Data access — Spring Data JPA/Hibernate (vs jOOQ / JDBC) | Accepted (baseline) |
| [0003](0003-postgresql-schema-strategy.md) | PostgreSQL schema strategy | Accepted |
| [0004](0004-uuid-strategy.md) | UUID strategy | Accepted (UUIDv7; timestamp exposure accepted risk) |
| [0005](0005-authentication-architecture.md) | Authentication architecture | Accepted (2026-11-12, owner) |
| [0006](0006-rbac-implementation.md) | RBAC implementation | Accepted (2026-11-12, owner) |
| [0007](0007-audit-architecture.md) | Audit architecture | Accepted (2026-11-12, owner) |
| [0008](0008-api-versioning-and-conventions.md) | API versioning and conventions | Accepted (2026-11-12, owner) |
| [0009](0009-encryption-and-secrets.md) | Encryption and secrets | Accepted (2026-11-12, owner) |
| [0010](0010-organization-ownership-and-tenant-isolation.md) | Organization ownership and tenant isolation | Accepted (2026-11-12, owner) |
| [0011](0011-transaction-boundaries.md) | Transaction boundaries | Accepted (2026-11-12, owner) |
| [0012](0012-financial-data-integrity.md) | Financial data integrity | Accepted (2026-11-12, owner) |
| [0013](0013-repository-and-build-layout.md) | Repository and build layout | Accepted |
| [0014](0014-per-module-observability.md) | Per-module observability (logging, metrics, monitoring) | Accepted |
| [0015](0015-database-roles-and-migration-privileges.md) | Database roles and migration privileges | Accepted |

## Open ADR candidates (not yet written)
Centralised log search (e.g. Loki; see ADR-0014), Backup/DR approach (pgBackRest vs managed PostgreSQL), hosting provider, frontend state/forms conventions, PDF generation library, search approach, ABDM adapter boundary (Stage 1.5), time zone handling for daily charges.

Note: these ADRs are condensed. Each has context, decision and consequences; some lack an explicit Problem/Options section and should be expanded when approved.
