/** Authorization in the UI is by permission code only, never by role name (CLAUDE.md section 6). */
export function hasPermission(permissions: readonly string[] | undefined, permission: string): boolean {
  return !!permissions && permissions.includes(permission)
}
