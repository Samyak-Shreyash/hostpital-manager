import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { listDoctors, practitionerKeys } from '../../api/practitioners'
import { inputClass, primaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'
import { StatusBadge, useCatalog } from './shared'

const PAGE_SIZE = 20

export function DoctorListPage() {
  const canCreate = useCan('practitioner:create')
  const catalog = useCatalog()
  const [q, setQ] = useState('')
  const [specialtyId, setSpecialtyId] = useState('')
  const [departmentId, setDepartmentId] = useState('')
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(0)
  const filters = { q: q.trim(), specialtyId, departmentId, status, page, size: PAGE_SIZE }
  const doctors = useQuery({ queryKey: practitionerKeys.doctors(filters), queryFn: () => listDoctors(filters), placeholderData: keepPreviousData })
  const totalPages = doctors.data ? Math.max(1, Math.ceil(doctors.data.totalItems / doctors.data.size)) : 1
  const reset = <T,>(set: (v: T) => void) => (v: T) => { set(v); setPage(0) }

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Doctors</h1>
        {canCreate && <Link to="/doctors/new" className={primaryButton}>Register doctor</Link>}
      </div>
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-4">
        <label className="text-sm">
          Search
          <input className={inputClass} value={q} onChange={(e) => reset(setQ)(e.target.value)} placeholder="Name or registration no." />
        </label>
        <label className="text-sm">
          Specialty
          <select className={inputClass} value={specialtyId} onChange={(e) => reset(setSpecialtyId)(e.target.value)}>
            <option value="">All</option>
            {catalog.specialties.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </label>
        <label className="text-sm">
          Department
          <select className={inputClass} value={departmentId} onChange={(e) => reset(setDepartmentId)(e.target.value)}>
            <option value="">All</option>
            {catalog.departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
        </label>
        <label className="text-sm">
          Status
          <select className={inputClass} value={status} onChange={(e) => reset(setStatus)(e.target.value)}>
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
          </select>
        </label>
      </div>
      {doctors.isError && <p role="alert" className="text-red-700">{errorMessage(doctors.error)}</p>}
      {doctors.isPending && <p role="status">Loading...</p>}
      {doctors.data && (
        <>
          <table className="w-full border-collapse bg-white text-left text-sm shadow">
            <thead>
              <tr className="border-b bg-slate-100">
                <th className="p-2">Name</th><th className="p-2">Registration no.</th><th className="p-2">Primary specialty</th><th className="p-2">Status</th>
              </tr>
            </thead>
            <tbody>
              {doctors.data.items.map((d) => (
                <tr key={d.id} className="border-b">
                  <td className="p-2"><Link className="text-blue-700 underline" to={`/doctors/${encodeURIComponent(d.id)}`}>{d.fullName}</Link></td>
                  <td className="p-2">{d.registrationNo}</td>
                  <td className="p-2">{catalog.specialtyName(d.primarySpecialtyId)}</td>
                  <td className="p-2"><StatusBadge status={d.status} /></td>
                </tr>
              ))}
              {doctors.data.items.length === 0 && <tr><td colSpan={4} className="p-2 text-slate-600">No doctors found.</td></tr>}
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
