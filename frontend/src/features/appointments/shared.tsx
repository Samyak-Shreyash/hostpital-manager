import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import type { AppointmentStatus } from '../../api/appointments'
import { getPatient, patientKeys, searchPatients, type PatientSummary } from '../../api/patients'
import { listDoctors, practitionerKeys, type Doctor } from '../../api/practitioners'
import { inputClass, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan } from '../auth/useSession'

export const STATUS_LABELS: Record<AppointmentStatus, string> = {
  SCHEDULED: 'Scheduled', CHECKED_IN: 'Checked in', IN_CONSULTATION: 'In consultation', COMPLETED: 'Completed',
  CANCELLED: 'Cancelled', NO_SHOW: 'No-show', RESCHEDULED: 'Rescheduled',
}

/** Statuses that hold the doctor's time (the rest free it). */
export const ACTIVE_STATUSES: AppointmentStatus[] = ['SCHEDULED', 'CHECKED_IN', 'IN_CONSULTATION']

/** Today (yyyy-mm-dd) in the facility timezone, not the browser's. */
export function todayIn(timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date())
}

export function useActiveDoctors() {
  const canRead = useCan('practitioner:read')
  const doctors = useQuery({
    queryKey: practitionerKeys.doctors({ status: 'ACTIVE', size: 100 }),
    queryFn: () => listDoctors({ status: 'ACTIVE', size: 100 }),
    enabled: canRead,
    staleTime: 60_000,
  })
  return { canRead, doctors: doctors.data?.items ?? ([] as Doctor[]), error: doctors.error }
}

/** Doctor chooser labelled by name; ids stay in the option values. */
export function DoctorSelect({ value, onChange }: { value: string; onChange: (id: string) => void }) {
  const { canRead, doctors, error } = useActiveDoctors()
  return (
    <div className="text-sm">
      <label htmlFor="appt-doctor" className="block font-medium">Doctor</label>
      <select id="appt-doctor" className={inputClass} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Select a doctor</option>
        {doctors.map((d) => <option key={d.id} value={d.id}>{d.fullName}</option>)}
      </select>
      {!canRead && <p className="mt-1 text-slate-600">You need permission to read doctors to choose one.</p>}
      {error != null && <p role="alert" className="mt-1 text-red-700">{errorMessage(error)}</p>}
    </div>
  )
}

/** Name and UHID of a patient known by id; falls back to a neutral label when it cannot be read. Never the id. */
export function PatientName({ patientId }: { patientId: string }) {
  const canRead = useCan('patient:read')
  const patient = useQuery({ queryKey: patientKeys.detail(patientId), queryFn: () => getPatient(patientId), enabled: canRead, staleTime: 60_000 })
  if (!patient.data) return <span className="text-slate-600">Patient</span>
  return <span><span className="font-medium">{patient.data.fullName}</span> <span className="text-slate-600">{patient.data.uhid}</span></span>
}

/** Search by name (3+ characters) and pick one patient; reports the choice up, shows it back as name and UHID. */
export function PatientPicker({ selected, onSelect, error }: { selected: PatientSummary | null; onSelect: (p: PatientSummary) => void; error?: string }) {
  const canSearch = useCan('patient:search')
  const [text, setText] = useState('')
  const [term, setTerm] = useState('')
  const [hint, setHint] = useState<string | null>(null)
  const results = useQuery({ queryKey: ['patients', 'pick', term], queryFn: () => searchPatients({ q: term, size: 10 }), enabled: canSearch && term.length >= 3 })
  const search = () => {
    const t = text.trim()
    if (t.length < 3) { setHint('Enter at least 3 characters of the name'); return }
    setHint(null)
    setTerm(t)
  }
  return (
    <div className="mb-4">
      <label htmlFor="pick-patient" className="block text-sm font-medium">Find patient</label>
      <div className="flex gap-2">
        <input
          id="pick-patient" className={inputClass} value={text} placeholder="Name"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); search() } }}
        />
        <button type="button" className={`${secondaryButton} mt-1`} onClick={search} disabled={!canSearch}>Search</button>
      </div>
      {!canSearch && <p className="mt-1 text-sm text-slate-600">You need permission to search patients.</p>}
      {hint && <p role="alert" className="mt-1 text-sm text-red-700">{hint}</p>}
      {results.isError && <p role="alert" className="mt-1 text-sm text-red-700">{errorMessage(results.error)}</p>}
      {results.data && results.data.items.length === 0 && <p className="mt-1 text-sm text-slate-600">No patients found.</p>}
      {results.data && results.data.items.length > 0 && (
        <ul aria-label="Matching patients" className="mt-2 space-y-1">
          {results.data.items.map((p) => (
            <li key={p.id}>
              <button type="button" className="w-full rounded border border-slate-200 px-3 py-1 text-left text-sm hover:bg-slate-100" onClick={() => onSelect(p)}>
                {p.fullName} ({p.uhid})
              </button>
            </li>
          ))}
        </ul>
      )}
      {selected && <p className="mt-2 text-sm">Selected: <strong>{selected.fullName}</strong> ({selected.uhid})</p>}
      {error && <p role="alert" className="mt-1 text-sm text-red-700">{error}</p>}
    </div>
  )
}
