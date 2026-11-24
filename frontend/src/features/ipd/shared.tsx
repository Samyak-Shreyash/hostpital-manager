import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { FormError, inputClass } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { ConflictNotice } from '../practitioners/shared'
import { getAvailability, ipdKeys, type AdmissionStatus } from './api'

export const BED_TAKEN_MESSAGE = 'Bed was just taken — pick another'

export const ADMISSION_STATUS_LABELS: Record<AdmissionStatus, string> = {
  ADMITTED: 'Admitted', DISCHARGE_INITIATED: 'Discharge initiated', DISCHARGED: 'Discharged', CANCELLED: 'Cancelled',
}

/** IPD wording for the codes the shared map words for appointments, then the shared map. */
export function ipdErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'BED_NOT_AVAILABLE': return BED_TAKEN_MESSAGE
      case 'BED_OCCUPIED': return 'This bed is occupied by an admitted patient, so it cannot be taken out of service.'
      case 'PATIENT_ALREADY_ADMITTED': return 'This patient already has an active admission.'
      case 'INVALID_STATE_TRANSITION': return 'The admission is not in a state that allows this. Reload to see its current status.'
      case 'IMMUTABLE_RECORD': return 'This record is final and can no longer be changed.'
      case 'DUPLICATE_WARD_CODE': return 'A ward with this code already exists.'
      case 'DUPLICATE_BED_NO': return 'This ward already has a bed with this number.'
      case 'DUPLICATE_FINAL_INVOICE': return 'A final invoice already exists for this admission.'
      case 'VALIDATION_ERROR': case 'BUSINESS_RULE_VIOLATION': return error.fieldErrors[0]?.message ?? error.message
    }
    if (error.status === 403) return 'You do not have permission to do this.'
  }
  return errorMessage(error)
}

/** A failed mutation: reload prompt for CONCURRENT_MODIFICATION, else the IPD message inline. */
export function IpdProblem({ error, what, onReload }: { error: unknown; what: string; onReload: () => void }) {
  if (!error) return null
  if (error instanceof ApiError && error.code === 'CONCURRENT_MODIFICATION') return <ConflictNotice what={what} onReload={onReload} />
  return <FormError message={ipdErrorMessage(error)} />
}

/** True for the 409 that means someone else got the bed first. */
export const isBedTaken = (error: unknown): boolean => error instanceof ApiError && error.code === 'BED_NOT_AVAILABLE'

export function useRefreshAvailability() {
  const queryClient = useQueryClient()
  return () => { void queryClient.invalidateQueries({ queryKey: ipdKeys.availability }) }
}

/** Chooser of beds that are free right now (ACTIVE, not occupied, in an ACTIVE ward), labelled ward and bed number. */
export function BedSelect({ id, value, onChange, excludeBedId, error }: {
  id: string; value: string; onChange: (id: string) => void; excludeBedId?: string; error?: string
}) {
  const availability = useQuery({ queryKey: ipdKeys.availability, queryFn: () => getAvailability() })
  const options = (availability.data?.wards ?? [])
    .filter((w) => w.status === 'ACTIVE')
    .flatMap((w) => w.beds
      .filter((b) => b.status === 'ACTIVE' && !b.occupied && b.id !== excludeBedId)
      .map((b) => ({ id: b.id, label: `${w.ward.name} — ${b.bedNo}${w.ward.careType === 'CRITICAL_CARE' ? ' (critical care)' : ''}` })))
  return (
    <div className="mb-4">
      <label htmlFor={id} className="block text-sm font-medium">Bed</label>
      <select id={id} className={inputClass} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="">Select an available bed</option>
        {options.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
      </select>
      {availability.isSuccess && options.length === 0 && <p className="mt-1 text-sm text-ink-muted">No beds are available right now.</p>}
      {availability.isError && <p role="alert" className="mt-1 text-sm text-danger">{ipdErrorMessage(availability.error)}</p>}
      {error && <p role="alert" className="mt-1 text-sm text-danger">{error}</p>}
    </div>
  )
}

const dateParts = (date: Date, timeZone: string) => {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-CA', { timeZone, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
      .formatToParts(date).map((p) => [p.type, p.value]),
  )
  return { y: +parts.year, mo: +parts.month, d: +parts.day, h: +parts.hour, mi: +parts.minute, s: +parts.second }
}

/** An instant as a datetime-local value (yyyy-mm-ddThh:mm) in the facility time zone. */
export function instantToLocal(iso: string, timeZone: string): string {
  const p = dateParts(new Date(iso), timeZone)
  const two = (n: number) => String(n).padStart(2, '0')
  return `${p.y}-${two(p.mo)}-${two(p.d)}T${two(p.h)}:${two(p.mi)}`
}

/** A facility-time-zone datetime-local value as a UTC instant (ISO). */
export function localToInstant(local: string, timeZone: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(local)
  if (!m) return ''
  const wanted = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5])
  const p = dateParts(new Date(wanted), timeZone)
  const shown = Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s)
  return new Date(wanted - (shown - wanted)).toISOString()
}

export function formatDateTime(iso: string | null, timeZone: string): string {
  if (!iso) return '—'
  return new Intl.DateTimeFormat('en-IN', { timeZone, dateStyle: 'medium', timeStyle: 'short' }).format(new Date(iso))
}
