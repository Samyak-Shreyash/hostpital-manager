# ADR-0009: Encryption and secrets
- **Status:** Proposed
- **Decision:** TLS everywhere externally; encrypted volumes/managed-PG encryption at rest; encrypted backups; application-level (AES-GCM) encryption for ABHA number and MFA secret with key from environment secret store, key id stored per row to permit rotation. Passwords hashed only. No secrets in git (CI secret scanning).
- **Consequences:** Application-level encrypted fields can't be searched by substring; hashed lookup column used where equality search is needed.
