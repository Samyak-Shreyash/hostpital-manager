package com.hms.practitioner.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * "A user is at most one doctor or one staff record" (V19). {@link #lock} serializes every link of one user until
 * commit (the same lock the V19 trigger takes), so a check made after it cannot be invalidated by a concurrent link.
 */
@Repository
public class PersonLinkRepository {

    public enum LinkKind { DOCTOR, STAFF }

    private final JdbcClient jdbc;

    PersonLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void lock(UUID userId) {
        jdbc.sql("SELECT 1 FROM hms.lock_person_user_link(?)").param(userId).query(Integer.class).list();
    }

    /** Under RLS only the caller's organization is visible; the user was already verified to belong to it. */
    public Optional<LinkKind> linkOf(UUID org, UUID userId) {
        return jdbc.sql("""
                SELECT 'DOCTOR' FROM hms.practitioner WHERE organization_id = ? AND user_id = ?
                UNION ALL
                SELECT 'STAFF' FROM hms.staff WHERE organization_id = ? AND user_id = ?
                LIMIT 1
                """).params(org, userId, org, userId).query(String.class).optional().map(LinkKind::valueOf);
    }
}
