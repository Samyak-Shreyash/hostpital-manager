-- [E1-7] User and role management (api-design.md section 3). Design: database-design.md section 15. Runs as hms_owner.
--
-- DELETE grants, justified explicitly (default grants are SELECT/INSERT/UPDATE only):
--   user_role        removing a role from a user.
--   role_permission  removing a permission from a role.
-- Both are pure mapping rows: no identity, no clinical or financial data. The change itself is audited
-- (USER_UPDATED / ROLE_UPDATED, with field names) in the same transaction, so nothing is lost by deleting the row.
-- Roles themselves are never deleted (no DELETE grant on role) and have no status: section 3 has no role
-- deactivation endpoint, and a role that no user holds is harmless.
GRANT DELETE ON hms.user_role, hms.role_permission TO hms_app;
