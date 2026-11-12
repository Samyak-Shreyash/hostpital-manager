import { useQuery } from '@tanstack/react-query'
import { fetchSession } from '../../api/auth'
import { hasPermission } from '../../lib/permissions'

export const SESSION_KEY = ['session'] as const

export function useSession() {
  return useQuery({ queryKey: SESSION_KEY, queryFn: fetchSession, retry: false, staleTime: 60_000 })
}

export function useCan(permission: string): boolean {
  const { data } = useSession()
  return hasPermission(data?.permissions, permission)
}
