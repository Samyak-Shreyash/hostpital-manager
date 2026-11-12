package com.hms.identity.infrastructure;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The RBAC tables of V4 (role, role_permission, user_role). Every query is scoped by organization as well as id,
 * so a mapping can never cross organizations, independently of RLS ([E1-6]).
 */
@Repository
public class RbacRepository {

    private final JdbcClient jdbc;

    public RbacRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> findRoleCodes(UUID organizationId, UUID userId) {
        return jdbc.sql("""
                SELECT r.code FROM hms.user_role ur
                JOIN hms.role r ON r.organization_id = ur.organization_id AND r.id = ur.role_id
                WHERE ur.organization_id = ? AND ur.user_id = ?
                ORDER BY r.code
                """)
                .params(organizationId, userId)
                .query(String.class)
                .list();
    }

    public List<String> findPermissionCodes(UUID organizationId, UUID userId) {
        return jdbc.sql("""
                SELECT DISTINCT rp.permission_code FROM hms.user_role ur
                JOIN hms.role_permission rp
                    ON rp.organization_id = ur.organization_id AND rp.role_id = ur.role_id
                WHERE ur.organization_id = ? AND ur.user_id = ?
                ORDER BY rp.permission_code
                """)
                .params(organizationId, userId)
                .query(String.class)
                .list();
    }

    /** Inserts the role unless its code already exists in the organization; returns whether it was inserted. */
    public boolean insertRoleIfAbsent(UUID id, UUID organizationId, String code, String name, Instant now) {
        return jdbc.sql("""
                INSERT INTO hms.role (id, organization_id, code, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (organization_id, code) DO NOTHING
                """)
                .params(id, organizationId, code, name, Timestamp.from(now), Timestamp.from(now))
                .update() == 1;
    }

    public void insertRolePermission(UUID organizationId, UUID roleId, String permissionCode) {
        jdbc.sql("INSERT INTO hms.role_permission (organization_id, role_id, permission_code) VALUES (?, ?, ?)")
                .params(organizationId, roleId, permissionCode)
                .update();
    }
}
