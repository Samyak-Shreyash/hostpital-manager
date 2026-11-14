import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { listStaff, practitionerKeys, STAFF_TYPES } from '../../api/practitioners'
import { inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { STAFF_TYPE_LABELS } from './schemas'
import { StatusBadge, useCatalog } from './shared'

const PAGE_SIZE = 20

/** Non-doctor personnel. Roles are not shown here (review notes Q8); they live on the linked user. */
export function StaffListPage() {
  const canCreate = useCan('staff:create')
  const catalog = useCatalog()
  const [q, setQ] = useState('')
  const [staffType, setStaffType] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const filters = { staffType, q: q.trim(), status, page, size: PAGE_SIZE }
  const staff = useQuery({ queryKey: practitionerKeys.staffList(filters), queryFn: () => listStaff(filters), placeholderData: keepPreviousData })
  const totalPages = staff.data ? Math.max(1, Math.ceil(staff.data.totalItems / staff.data.size)) : 1

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Staff</h1>
        {canCreate && <Link to="/staff/new" className={primaryButton}>Register staff</Link>}
      </div>
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
        <label className="text-sm">
          Search
          <input className={inputClass} value={q} onChange={(e) => { setQ(e.target.value); setPage(0) }} placeholder="Name" />
        </label>
        <label className="text-sm">
          Staff type
          <select className={inputClass} value={staffType} onChange={(e) => { setStaffType(e.target.value); setPage(0) }}>
            <option value="">All</option>
            {STAFF_TYPES.map((t) => <option key={t} value={t}>{STAFF_TYPE_LABELS[t]}</option>)}
          </select>
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
      {staff.isError && <p role="alert" className="text-red-700">{errorMessage(staff.error)}</p>}
      {staff.isPending && <p role="status">Loading...</p>}
      {staff.data && (
        <>
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                <th className="p-2">Name</th><th className="p-2">Type</th><th className="p-2">Designation</th><th className="p-2">Department</th><th className="p-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {staff.data.items.map((s) => (
                <tr key={s.id} className="border-b">
                  <td className="p-2"><Link className="text-blue-700 underline" to={`/staff/${encodeURIComponent(s.id)}`}>{s.fullName}</Link></td>
                  <td className="p-2">{STAFF_TYPE_LABELS[s.staffType] ?? s.staffType}</td>
                  <td className="p-2">{s.designation ?? '—'}</td>
                  <td className="p-2">{catalog.departmentName(s.departmentId)}</td>
                  <td className="p-2"><StatusBadge status={s.status} /></td>
                </tr>
              ))}
              {staff.data.items.length === 0 && <tr><td colSpan={5} className="p-2 text-slate-600">No staff found.</td></tr>}
            </tbody>
          </table>
          <div className="mt-3 flex items-center gap-3 text-sm">
            <button type="button" className="underline disabled:opacity-50" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {totalPages}</span>
            <button type="button" className="underline disabled:opacity-50" disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}
    </section>
  )
}
