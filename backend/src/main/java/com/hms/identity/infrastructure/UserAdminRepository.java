package com.hms.identity.infrastructure;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * User listing and session revocation for user management ([E1-7]). {@code app_user} is outside RLS
 * (database-design.md section 14), so every query here filters by the caller's organization explicitly.
 */
@Repository
public class UserAdminRepository {

    public record UserRow(UUID id, String email, String fullName, String status, boolean mustChangePassword,
            long version, Instant createdAt, Instant updatedAt) {
    }

    private static final String FILTERED = """
            FROM hms.app_user u
            WHERE u.organization_id = :org
              AND (CAST(:status AS text) IS NULL OR u.status = CAST(:status AS text))
              AND (CAST(:role AS text) IS NULL OR EXISTS (
                    SELECT 1 FROM hms.user_role ur JOIN hms.role r
                      ON r.organization_id = ur.organization_id AND r.id = ur.role_id
                    WHERE ur.organization_id = u.organization_id AND ur.user_id = u.id
                      AND r.code = CAST(:role AS text)))
              AND (NOT CAST(:hasIds AS boolean) OR u.id IN (:ids))
              AND (CAST(:like AS text) IS NULL
                   OR lower(u.full_name) LIKE CAST(:like AS text) ESCAPE '\\'
                   OR u.email LIKE CAST(:like AS text) ESCAPE '\\')
            """;

    /** Sort keys accepted by {@link #list}; the SQL comes from this map only, never from request text. */
    public static final java.util.Map<String, String> SORT_COLUMNS = java.util.Map.of(
            "name", "lower(u.full_name)", "email", "u.email", "status", "u.status", "createdAt", "u.created_at");

    /** Optional filters; null (or an empty id list) means "not filtered". */
    public record Filter(String status, String roleCode, String likePattern, List<UUID> ids) {
    }

    /** Placeholder for the IN list when there is no id filter (an empty IN list is invalid SQL). */
    private static final List<UUID> NO_IDS = List.of(new UUID(0, 0));

    private final JdbcClient jdbc;

    public UserAdminRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The organization's user, row share-locked until commit; empty for an unknown or other-org id. */
    public java.util.Optional<UserRow> findForShare(UUID organizationId, UUID userId) {
        return jdbc.sql("""
                SELECT id, email, full_name, status, must_change_password, version, created_at, updated_at
                FROM hms.app_user WHERE organization_id = ? AND id = ? FOR SHARE
                """).params(organizationId, userId)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("full_name"), rs.getString("status"), rs.getBoolean("must_change_password"),
                        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .optional();
    }

    /** Serializes first-run bootstrap across application instances; released at the end of the transaction. */
    public void lockBootstrap() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('hms.bootstrap'))").query().singleValue();
    }

    /**
     * Null filters are ignored. {@code likePattern} is already lower-cased, escaped and wrapped in %. {@code sortKey}
     * must be a key of {@link #SORT_COLUMNS}; ties break on id so paging is stable.
     */
    public List<UserRow> list(UUID organizationId, Filter filter, String sortKey, boolean descending,
            int limit, long offset) {
        String column = java.util.Objects.requireNonNull(SORT_COLUMNS.get(sortKey), "sortKey");
        String direction = descending ? " DESC" : " ASC";
        return bind(jdbc.sql("SELECT u.id, u.email, u.full_name, u.status, u.must_change_password, u.version, "
                        + "u.created_at, u.updated_at " + FILTERED + " ORDER BY " + column + direction
                        + ", u.id" + direction + " LIMIT :limit OFFSET :offset"), organizationId, filter)
                .param("limit", limit).param("offset", offset)
                .query((rs, n) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("email"),
                        rs.getString("full_name"), rs.getString("status"), rs.getBoolean("must_change_password"),
                        rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public long count(UUID organizationId, Filter filter) {
        return bind(jdbc.sql("SELECT count(*) " + FILTERED), organizationId, filter).query(Long.class).single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID organizationId, Filter f) {
        boolean hasIds = f.ids() != null && !f.ids().isEmpty();
        return spec.param("org", organizationId).param("status", f.status()).param("role", f.roleCode())
                .param("like", f.likePattern()).param("hasIds", hasIds).param("ids", hasIds ? f.ids() : NO_IDS);
    }

    /**
     * Ends the user's server-side sessions ({@code principal_name} is the user id string, see
     * {@code AuthenticatedPrincipal#toString}); their attributes go with them (ON DELETE CASCADE).
     * {@code keepSessionId} is the caller's own session when they changed their own account, else null.
     * Returns the number of sessions ended.
     */
    public int deleteSessions(UUID userId, String keepSessionId) {
        return jdbc.sql("""
                DELETE FROM hms.spring_session
                WHERE principal_name = :principal
                  AND (CAST(:keep AS text) IS NULL OR session_id <> CAST(:keep AS text))
                """)
                .param("principal", userId.toString()).param("keep", keepSessionId)
                .update();
    }
}
