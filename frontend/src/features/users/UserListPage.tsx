import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { listUsers } from '../../api/users'
import { inputClass, primaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { errorMessage } from '../auth/messages'

const PAGE_SIZE = 20

export function UserListPage() {
  const canCreate = useCan('user:create')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const filters = { q: q.trim(), status, page, size: PAGE_SIZE }
  const users = useQuery({ queryKey: ['users', filters], queryFn: () => listUsers(filters), placeholderData: keepPreviousData })
  const totalPages = users.data ? Math.max(1, Math.ceil(users.data.totalItems / users.data.size)) : 1

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Users</h1>
        {canCreate && <Link to="/users/new" className={primaryButton}>New user</Link>}
      </div>
      <div className="mb-4 flex gap-3">
        <label className="flex-1 text-sm">
          Search
          <input className={inputClass} value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} placeholder="Name or email" />
        </label>
        <label className="text-sm">
          Status
          <select className={inputClass} value={status} onChange={(e) => { setStatus(e.target.value); setPage(0) }}>
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </label>
      </div>
      {users.isError && <p role="alert" className="text-red-700">{errorMessage(users.error)}</p>}
      {users.isPending && <p role="status">Loading...</p>}
      {users.data && (
        <>
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                <th className="p-2">Name</th><th className="p-2">Email</th><th className="p-2">Roles</th><th className="p-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {users.data.items.map((u) => (
                <tr key={u.id} className="border-b">
                  <td className="p-2"><Link className="text-blue-700 underline" to={`/users/${encodeURIComponent(u.id)}`}>{u.fullName}</Link></td>
                  <td className="p-2">{u.email}</td>
                  <td className="p-2">{u.roleCodes.join(', ')}</td>
                  <td className="p-2">{u.status}</td>
                </tr>
              ))}
              {users.data.items.length === 0 && <tr><td className="p-2" colSpan={4}>No users found.</td></tr>}
            </tbody>
          </table>
          <div className="mt-3 flex items-center gap-3 text-sm">
            <button type="button" className="underline disabled:opacity-40" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" className="underline disabled:opacity-40" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}
    </section>
  )
}
