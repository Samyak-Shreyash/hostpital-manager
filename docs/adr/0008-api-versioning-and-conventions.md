# ADR-0008: API versioning and conventions
- **Status:** Proposed
- **Decision:** URI major version `/api/v1`. Additive changes are non-breaking; breaking changes → `/api/v2` alongside. JSON, camelCase, ISO-8601 UTC instants, money as decimal **strings** (`"1250.00"`) to avoid float loss, cursor or page/size pagination (`page`,`size`, max 100), RFC 7807 errors with stable `code`, `ETag`/`If-Match` (or `version` field) for optimistic concurrency, `Idempotency-Key` on money-moving POSTs. OpenAPI generated and diffed in CI.
