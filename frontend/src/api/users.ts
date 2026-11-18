import { api } from './client'
import type { Page, Role, UserSummary, UserWithTemporaryPassword } from './types'

export interface UserFilters {
  status?: string
  role?: string
  q?: string
  /** Only these users (at most 100); used to show names for ids, e.g. audit actors. */
  ids?: string[]
  /** name | email | status | createdAt, '-' prefix for descending. */
  sort?: string
  page?: number
  size?: number
}

export function listUsers(filters: UserFilters) {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (Array.isArray(value)) value.forEach((v) => params.append(key, v))
    else if (value !== undefined && value !== '') params.set(key, String(value))
  }
  const query = params.toString()
  return api.get<Page<UserSummary>>(`/users${query ? `?${query}` : ''}`)
}

/** All or nothing; the temporary passwords are in this response only. */
export const importUsers = (users: { email: string; fullName: string; roleCodes: string[] }[]) =>
  api.post<{ items: UserWithTemporaryPassword[] }>('/users/import', { users })

export const getUser = (id: string) => api.get<UserSummary>(`/users/${encodeURIComponent(id)}`)
export const createUser = (body: { email: string; fullName: string; roleCodes: string[] }) =>
  api.post<UserWithTemporaryPassword>('/users', body)
export const updateUser = (id: string, body: { version: number; fullName?: string; roleCodes?: string[] }) =>
  api.patch<UserSummary>(`/users/${encodeURIComponent(id)}`, body)
export const setUserActive = (id: string, active: boolean) =>
  api.post<UserSummary>(`/users/${encodeURIComponent(id)}/${active ? 'activate' : 'deactivate'}`)
export const resetUserPassword = (id: string) =>
  api.post<UserWithTemporaryPassword>(`/users/${encodeURIComponent(id)}/reset-password`)

export const listRoles = () => api.get<Role[]>('/roles')
export const listPermissions = () => api.get<string[]>('/permissions')
export const createRole = (body: { code: string; name: string; permissionCodes: string[] }) =>
  api.post<Role>('/roles', body)
export const updateRole = (id: string, body: { version: number; name?: string; permissionCodes?: string[] }) =>
  api.patch<Role>(`/roles/${encodeURIComponent(id)}`, body)
