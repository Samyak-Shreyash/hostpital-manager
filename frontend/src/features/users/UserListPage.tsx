import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { listRoles, listUsers } from '../../api/users'
import { inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { errorMessage } from '../auth/messages'

const PAGE_SIZES = [20, 50, 100]

type SortKey = 'name' | 'email' | 'status'

/** Users carry role codes; show names. Without role:read (or while loading) the code is the fallback. */
export function useRoleNames(): (codes: string[]) => string {
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles, enabled: useCan('role:read') })
  const names = new Map((roles.data ?? []).map((r) => [r.code, r.name]))
  return (codes) => codes.map((c) => names.get(c) ?? c).join(', ')
}

export function UserListPage() {
  const canCreate = useCan('user:create')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [role, setRole] = useState('')
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: 'name', desc: false })
  const [size, setSize] = useState(PAGE_SIZES[0])
  const [page, setPage] = useState(0)
  const roles = useQuery({ queryKey: ['roles'], queryFn: listRoles, enabled: useCan('role:read') })
  const filters = { q: q.trim(), status, role, sort: `${sort.desc ? '-' : ''}${sort.key}`, page, size }
  const users = useQuery({ queryKey: ['users', filters], queryFn: () => listUsers(filters), placeholderData: keepPreviousData })
  const roleNames = useRoleNames()
  const totalPages = users.data ? Math.max(1, Math.ceil(users.data.totalItems / users.data.size)) : 1

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Users</h1>
        {canCreate && (
          <div className="flex gap-2">
            <Link to="/users/import" className={secondaryButton}>Import CSV</Link>
            <Link to="/users/new" className={primaryButton}>New user</Link>
          </div>
        )}
      </div>
      <div className="mb-4 flex gap-3">
        <label className="flex-1 text-sm">
          Search
          <input className={inputClass} value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} placeholder="Name or email" />
        </label>
        {roles.data && (
          <label className="text-sm">
            Role
            <select className={inputClass} value={role} onChange={(e) => { setRole(e.target.value); setPage(0) }}>
              <option value="">All</option>
              {roles.data.map((r) => <option key={r.code} value={r.code}>{r.name}</option>)}
            </select>
          </label>
        )}
        <label className="text-sm">
          Status
          <select className={inputClass} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </label>
      </div>
      {users.isError && <p role="alert" className="text-danger">{errorMessage(users.error)}</p>}
      {users.isPending && <p role="status">Loading...</p>}
      {users.data && (
        <>
          <table className="w-full border-collapse bg-surface text-left text-sm">
            <thead>
              <tr className="border-b bg-muted">
                <SortHeader label="Name" sortKey="name" sort={sort} onSort={(s) => { setSort(s); setPage(0) }} />
                <SortHeader label="Email" sortKey="email" sort={sort} onSort={(s) => { setSort(s); setPage(0) }} />
                <th className="p-2">Roles</th>
                <SortHeader label="Status" sortKey="status" sort={sort} onSort={(s) => { setSort(s); setPage(0) }} />
              </tr>
            </thead>
            <tbody>
              {users.data.items.map((u) => (
                <tr key={u.id} className="border-b">
                  <td className="p-2"><Link className="text-primary underline" to={`/users/${encodeURIComponent(u.id)}`}>{u.fullName}</Link></td>
                  <td className="p-2">{u.email}</td>
                  <td className="p-2">{roleNames(u.roleCodes)}</td>
                  <td className="p-2">
                    <span className={`rounded px-2 py-0.5 text-xs font-medium ${u.status === 'ACTIVE' ? 'bg-success-soft text-success' : 'bg-muted text-ink-muted'}`}>
                      {u.status === 'ACTIVE' ? 'Active' : 'Inactive'}
                    </span>
                  </td>
                </tr>
              ))}
              {users.data.items.length === 0 && <tr><td className="p-2" colSpan={4}>No users found.</td></tr>}
            </tbody>
          </table>
          <div className="mt-3 flex items-center gap-3 text-sm">
            <button type="button" className={secondaryButton} disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" className={secondaryButton} disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
            <label className="ml-auto flex items-center gap-2">
              Per page
              <select className="rounded border border-border-strong px-2 py-1" value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0) }}>
                {PAGE_SIZES.map((n) => <option key={n} value={n}>{n}</option>)}
              </select>
            </label>
          </div>
        </>
      )}
    </section>
  )
}

function SortHeader({ label, sortKey, sort, onSort }: {
  label: string
  sortKey: SortKey
  sort: { key: SortKey; desc: boolean }
  onSort: (sort: { key: SortKey; desc: boolean }) => void
}) {
  const active = sort.key === sortKey
  return (
    <th className="p-2" aria-sort={active ? (sort.desc ? 'descending' : 'ascending') : 'none'}>
      <button type="button" className="font-semibold hover:underline" onClick={() => onSort({ key: sortKey, desc: active && !sort.desc })}>
        {label}{active ? (sort.desc ? ' ▼' : ' ▲') : ''}
      </button>
    </th>
  )
}
