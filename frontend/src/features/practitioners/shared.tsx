import { useQuery } from '@tanstack/react-query'
import type { UseFormRegisterReturn } from 'react-hook-form'
import { listDepartments, listSpecialties, practitionerKeys, type CatalogItem } from '../../api/practitioners'
import { listUsers } from '../../api/users'
import { inputClass } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'

/** Departments and specialties ([E2-2]) with id -> name lookups; inactive entries stay resolvable for labels. */
export function useCatalog() {
  const canRead = useCan('catalog:read')
  const specialties = useQuery({ queryKey: practitionerKeys.specialties, queryFn: listSpecialties, enabled: canRead, staleTime: 60_000 })
  const departments = useQuery({ queryKey: practitionerKeys.departments, queryFn: listDepartments, enabled: canRead, staleTime: 60_000 })
  const names = (items: CatalogItem[] | undefined) => new Map((items ?? []).map((i) => [i.id, i.name]))
  const specialtyNames = names(specialties.data?.items)
  const departmentNames = names(departments.data?.items)
  return {
    canRead,
    specialties: specialties.data?.items ?? [],
    departments: departments.data?.items ?? [],
    /** Never falls back to the id: an unresolvable entry is shown as "Unknown". */
    specialtyName: (id: string) => specialtyNames.get(id) ?? 'Unknown',
    departmentName: (id: string | null) => (id ? departmentNames.get(id) ?? 'Unknown' : '—'),
    error: specialties.error ?? departments.error,
  }
}

/** Picks an active user ([E1-7] list) to link; labelled by name and email, never by id. */
export function UserPicker({ id, registration, error }: { id: string; registration: UseFormRegisterReturn; error?: string }) {
  const canRead = useCan('user:read')
  const users = useQuery({
    queryKey: ['users', { status: 'ACTIVE', size: 100 }],
    queryFn: () => listUsers({ status: 'ACTIVE', size: 100 }),
    enabled: canRead,
  })
  return (
    <div className="mb-4">
      <label htmlFor={id} className="block text-sm font-medium">User account</label>
      <select id={id} className={inputClass} {...registration}>
        <option value="">Select a user</option>
        {users.data?.items.map((u) => (
          <option key={u.id} value={u.id}>{u.fullName} ({u.email})</option>
        ))}
      </select>
      {!canRead && <p className="mt-1 text-sm text-slate-600">You need permission to read users to link an account.</p>}
      {users.isError && <p role="alert" className="mt-1 text-sm text-red-700">{errorMessage(users.error)}</p>}
      {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
    </div>
  )
}

export function StatusBadge({ status }: { status: string }) {
  const active = status === 'ACTIVE'
  return <span className={`rounded px-2 py-0.5 text-xs ${active ? 'bg-green-100 text-green-900' : 'bg-slate-200 text-slate-700'}`}>{active ? 'Active' : 'Inactive'}</span>
}

export function ConflictNotice({ what, onReload }: { what: string; onReload: () => void }) {
  return (
    <div role="alert" className="mb-4 rounded bg-amber-50 p-3 text-sm">
      Someone else changed this {what}. Reload to see the latest version, then reapply your change.{' '}
      <button type="button" className="text-blue-700 underline" onClick={onReload}>Reload latest version</button>
    </div>
  )
}
