# ADR-0002: Data access — Spring Data JPA/Hibernate (vs jOOQ / JDBC)
- **Status:** Accepted (baseline)
- **Context:** Mostly CRUD + aggregates; a few complex queries (reports, availability, search).
- **Options:** JPA only; jOOQ only; JPA for writes + SQL for reads.
- **Decision:** JPA for aggregate persistence. Reports and complex reads use native SQL/`JdbcClient` into DTOs (not entities). No `EAGER` fetch; no cross-module entity associations; `open-in-view=false`; N+1 guarded by tests on key queries.
- **Consequences:** + productivity. − Hibernate pitfalls (lazy loading, flush order); mitigated by conventions and Testcontainers repository tests. jOOQ can be introduced module-locally later if reporting grows.
