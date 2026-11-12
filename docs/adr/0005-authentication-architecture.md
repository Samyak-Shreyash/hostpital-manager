# ADR-0005: Authentication architecture
- **Status:** Accepted (2026-11-12, owner) — **deviates from charter ADR "JWT + refresh cookie"**
- **Context:** Same-origin SPA served behind one proxy, staff users only, need immediate revocation (deactivate user, lost workstation), "session management" is an explicit requirement.
- **Options:** (A) Server-side sessions (Spring Session JDBC, HttpOnly cookie, CSRF token); (B) short-lived JWT + rotating refresh token; (C) external IdP (Keycloak).
- **Decision:** A. Argon2id or BCrypt (cost ≥ 12) hashing, lockout, idle/absolute timeouts, session id rotation on login, `SameSite=Lax` + CSRF token, optional TOTP MFA for admin/doctor. Sessions in PostgreSQL (no Redis).
- **Consequences:** + trivial revocation, no token storage in JS (XSS-safer), fewer moving parts. − Stateful (fine at this scale; horizontal scale works via shared DB); mobile/3rd-party API clients later need a token flow (Stage 3 may introduce OIDC/Keycloak; the `identity` module hides the mechanism behind an `AuthenticatedPrincipal`).
