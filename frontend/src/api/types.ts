/** Ids are opaque strings; the client never parses or validates their format (api-design.md section 1). */
export interface Session {
  user: { id: string; fullName: string; roles: string[]; mustChangePassword: boolean }
  permissions: string[]
  organization: { id: string; name: string; timezone: string }
}

export interface UserSummary {
  id: string
  email: string
  fullName: string
  status: 'ACTIVE' | 'INACTIVE'
  mustChangePassword: boolean
  roleCodes: string[]
  version: number
  createdAt: string
  updatedAt: string
}

/** Create and reset-password responses only: the temporary password is shown once and never stored. */
export type UserWithTemporaryPassword = UserSummary & { temporaryPassword: string }

export interface Page<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
}

export interface Role {
  id: string
  code: string
  name: string
  permissions: string[]
  version: number
  createdAt: string
  updatedAt: string
}
