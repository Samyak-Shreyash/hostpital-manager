import { api, ApiError } from './client'
import type { Session } from './types'

/** `null` means "not signed in" (401), which is a normal state for the guards, not an error. */
export async function fetchSession(): Promise<Session | null> {
  try {
    return await api.get<Session>('/auth/me')
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

export const login = (email: string, password: string) => api.post<Session>('/auth/login', { email, password })
export const logout = () => api.post<void>('/auth/logout')
export const changePassword = (currentPassword: string, newPassword: string) =>
  api.post<void>('/auth/change-password', { currentPassword, newPassword })
