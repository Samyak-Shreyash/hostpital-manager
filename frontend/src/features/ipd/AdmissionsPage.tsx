import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { inputClass, primaryButton } from '../../components/ui'
import { useCan, useSession } from '../auth/useSession'
import { ipdKeys, listAdmissions, type AdmissionStatus } from './api'
import { ADMISSION_STATUS_LABELS, formatDateTime, ipdErrorMessage } from './shared'

/** Admissions list ([E8-8]); the entry point to the admission detail. */
export function AdmissionsPage() {
  const timeZone = useSession().data?.organization.timezone ?? 'UTC'
  const canCreate = useCan('admission:create')
  const [status, setStatus] = useState<AdmissionStatus | ''>('ADMITTED')
  const list = useQuery({ queryKey: [...ipdKeys.admissions, status], queryFn: () => listAdmissions({ status, size: 100 }) })
  return (
    <section>
      <div className="mb-4 flex flex-wrap items-center gap-4">
        <h1 className="text-2xl font-semibold">Admissions</h1>
        {canCreate && <Link to="/admissions/new" className={`${primaryButton} ml-auto`}>Admit patient</Link>}
      </div>
      <div className="mb-4 max-w-xs">
        <label htmlFor="adm-status" className="block text-sm font-medium">Status</label>
        <select id="adm-status" className={inputClass} value={status} onChange={(e) => setStatus(e.target.value as AdmissionStatus | '')}>
          <option value="">All</option>
          {(Object.keys(ADMISSION_STATUS_LABELS) as AdmissionStatus[]).map((s) => <option key={s} value={s}>{ADMISSION_STATUS_LABELS[s]}</option>)}
        </select>
      </div>
      {list.isPending && <p role="status">Loading...</p>}
      {list.isError && <p role="alert" className="text-danger">{ipdErrorMessage(list.error)}</p>}
      {list.data && list.data.items.length === 0 && <p className="text-ink-muted">No admissions found.</p>}
      <ul className="space-y-2">
        {list.data?.items.map((a) => (
          <li key={a.id} className="flex flex-wrap items-center gap-3 rounded-xl border border-border bg-surface p-3">
            <Link className="font-medium text-primary underline" to={`/admissions/${a.id}`}>{a.admissionNo}</Link>
            <span>{a.patient.name ?? 'Patient'} <span className="text-sm text-ink-muted">{a.patient.uhid}</span></span>
            <span className="text-sm">{a.currentBed ? `${a.currentBed.ward} — ${a.currentBed.bedNo}` : 'No bed'}</span>
            <span className="text-sm text-ink-muted">{formatDateTime(a.admittedAt, timeZone)}</span>
            <span className="ml-auto rounded bg-muted px-2 py-0.5 text-xs">{ADMISSION_STATUS_LABELS[a.status]}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}
