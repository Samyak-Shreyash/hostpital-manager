package com.hms.identity.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    public record RoleRow(UUID id, String code, String name, long version, Instant createdAt, Instant updatedAt) {
    }

    /**
     * Serialises the changes that can remove the last administrator of an organization (they all lock this row),
     * which a plain count-then-write could not stop when two requests race.
     */
    public void lockOrganization(UUID organizationId) {
        jdbc.sql("SELECT id FROM hms.organization WHERE id = ? FOR UPDATE").param(organizationId)
                .query(UUID.class).optional().orElseThrow();
    }

    /**
     * Active users who can manage both users and roles (permissions {@code user:update} and {@code role:manage}
     * through their roles): the "system administrators" the last-administrator rule protects. Defined by
     * permission, not by role name (CLAUDE.md section 6), so a renamed or custom role counts too.
     */
    public int countActiveAdministrators(UUID organizationId) {
        return jdbc.sql("""
                SELECT count(*) FROM hms.app_user u
                WHERE u.organization_id = :org AND u.status = 'ACTIVE'
                  AND EXISTS (SELECT 1 FROM hms.user_role ur JOIN hms.role_permission rp
                              ON rp.organization_id = ur.organization_id AND rp.role_id = ur.role_id
                              WHERE ur.organization_id = u.organization_id AND ur.user_id = u.id
                                AND rp.permission_code = 'user:update')
                  AND EXISTS (SELECT 1 FROM hms.user_role ur JOIN hms.role_permission rp
                              ON rp.organization_id = ur.organization_id AND rp.role_id = ur.role_id
                              WHERE ur.organization_id = u.organization_id AND ur.user_id = u.id
                                AND rp.permission_code = 'role:manage')
                """)
                .param("org", organizationId).query(Integer.class).single();
    }

    /** Role code to id for the codes that exist in this organization; unknown codes are simply absent. */
    public Map<String, UUID> findRoleIdsByCodes(UUID organizationId, Collection<String> codes) {
        Map<String, UUID> found = new LinkedHashMap<>();
        if (codes.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT code, id FROM hms.role WHERE organization_id = :org AND code IN (:codes)")
                .param("org", organizationId).param("codes", codes)
                .query((rs, n) -> found.put(rs.getString("code"), rs.getObject("id", UUID.class)))
                .list();
        return found;
    }

    public void insertUserRole(UUID organizationId, UUID userId, UUID roleId) {
        jdbc.sql("INSERT INTO hms.user_role (organization_id, user_id, role_id) VALUES (?, ?, ?)")
                .params(organizationId, userId, roleId).update();
    }

    public void deleteUserRole(UUID organizationId, UUID userId, UUID roleId) {
        jdbc.sql("DELETE FROM hms.user_role WHERE organization_id = ? AND user_id = ? AND role_id = ?")
                .params(organizationId, userId, roleId).update();
    }

    /** Role codes per user, for a page of users in one query. */
    public Map<UUID, List<String>> findRoleCodesByUsers(UUID organizationId, Collection<UUID> userIds) {
        Map<UUID, List<String>> byUser = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return byUser;
        }
        jdbc.sql("""
                SELECT ur.user_id, r.code FROM hms.user_role ur
                JOIN hms.role r ON r.organization_id = ur.organization_id AND r.id = ur.role_id
                WHERE ur.organization_id = :org AND ur.user_id IN (:users)
                ORDER BY r.code
                """)
                .param("org", organizationId).param("users", userIds)
                .query((rs, n) -> byUser.computeIfAbsent(rs.getObject("user_id", UUID.class),
                        k -> new ArrayList<>()).add(rs.getString("code")))
                .list();
        return byUser;
    }

    public Optional<RoleRow> findRoleForUpdate(UUID organizationId, UUID roleId) {
        return jdbc.sql("""
                SELECT id, code, name, version, created_at, updated_at FROM hms.role
                WHERE organization_id = ? AND id = ? FOR UPDATE
                """)
                .params(organizationId, roleId).query(RbacRepository::roleRow).optional();
    }

    public Optional<RoleRow> findRole(UUID organizationId, UUID roleId) {
        return jdbc.sql("""
                SELECT id, code, name, version, created_at, updated_at FROM hms.role
                WHERE organization_id = ? AND id = ?
                """)
                .params(organizationId, roleId).query(RbacRepository::roleRow).optional();
    }

    public List<RoleRow> findRoles(UUID organizationId) {
        return jdbc.sql("""
                SELECT id, code, name, version, created_at, updated_at FROM hms.role
                WHERE organization_id = ? ORDER BY code
                """)
                .param(organizationId).query(RbacRepository::roleRow).list();
    }

    private static RoleRow roleRow(ResultSet rs, int rowNumber) throws SQLException {
        return new RoleRow(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    /** Permission codes per role, sorted. */
    public Map<UUID, List<String>> findPermissionCodesByRoles(UUID organizationId, Collection<UUID> roleIds) {
        Map<UUID, List<String>> byRole = new LinkedHashMap<>();
        if (roleIds.isEmpty()) {
            return byRole;
        }
        jdbc.sql("""
                SELECT role_id, permission_code FROM hms.role_permission
                WHERE organization_id = :org AND role_id IN (:roles) ORDER BY permission_code
                """)
                .param("org", organizationId).param("roles", roleIds)
                .query((rs, n) -> byRole.computeIfAbsent(rs.getObject("role_id", UUID.class),
                        k -> new ArrayList<>()).add(rs.getString("permission_code")))
                .list();
        return byRole;
    }

    public void deleteRolePermission(UUID organizationId, UUID roleId, String permissionCode) {
        jdbc.sql("DELETE FROM hms.role_permission WHERE organization_id = ? AND role_id = ? AND permission_code = ?")
                .params(organizationId, roleId, permissionCode).update();
    }

    /** Sets the name and moves the version; the caller holds the row lock and has checked the version. */
    public void updateRole(UUID organizationId, UUID roleId, String name, Instant now) {
        jdbc.sql("""
                UPDATE hms.role SET name = ?, version = version + 1, updated_at = ?
                WHERE organization_id = ? AND id = ?
                """)
                .params(name, Timestamp.from(now), organizationId, roleId).update();
    }

    public List<UUID> findUserIdsHoldingRole(UUID organizationId, UUID roleId) {
        return jdbc.sql("SELECT user_id FROM hms.user_role WHERE organization_id = ? AND role_id = ?")
                .params(organizationId, roleId).query(UUID.class).list();
    }
}
