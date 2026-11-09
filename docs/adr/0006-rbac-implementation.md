# ADR-0006: RBAC implementation
- **Status:** Proposed
- **Context:** "Permissions can evolve without hardcoding roles."
- **Decision:** Permissions are code-defined constants (`patient:read`…) seeded to the `permission` table; roles are org-owned data mapping to permissions; users have N roles. Code checks **permissions only** (`@PreAuthorize("hasAuthority('billing:refund')")`), never role names. Effective authorities loaded at login and refreshed on role change (sessions invalidated). Object-level rules (e.g., org match, doctor-sees-own-patients if later needed) live in application services via a `AccessPolicy` interface; R1 policy: any user with `patient:read` can read any patient in their org, **every access audited** (clinic-scale trust model; flag for review).
- **Consequences:** + flexible. − Permission list is a contract: new endpoints need a permission, enforced by a test that fails when an endpoint lacks a `@PreAuthorize`.
