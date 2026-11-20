import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { appointmentKeys, getQueue } from '../../api/appointments'
import { inputClass } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { DoctorSelect, STATUS_LABELS } from './shared'

/** How often the board re-reads the live queue (api-design.md section 7: polling, no push). */
export const QUEUE_POLL_MS = 15_000

/** The doctor's live queue by token. The date is optional: the server defaults to today in the facility timezone. */
export function QueueBoardPage() {
  const [doctorId, setDoctorId] = useState('')
  const [date, setDate] = useState('')
  const queue = useQuery({
    queryKey: appointmentKeys.queue(doctorId, date),
    queryFn: () => getQueue(doctorId, date || undefined),
    enabled: !!doctorId,
    refetchInterval: QUEUE_POLL_MS,
  })
  return (
    <section>
      <h1 className="mb-4 text-2xl font-semibold">Queue</h1>
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <DoctorSelect value={doctorId} onChange={setDoctorId} />
        <div className="text-sm">
          <label htmlFor="queue-date" className="block font-medium">Date</label>
          <input id="queue-date" type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          <p className="mt-1 text-slate-600">Leave empty for today.</p>
        </div>
      </div>
      {!doctorId && <p className="text-slate-600">Choose a doctor to see their queue.</p>}
      {queue.isError && <p role="alert" className="text-red-700">{errorMessage(queue.error)}</p>}
      {doctorId && queue.isPending && !queue.isError && <p role="status">Loading...</p>}
      {queue.data && <p className="mb-2 text-sm text-slate-600">Updates every {QUEUE_POLL_MS / 1000} seconds.</p>}
      {queue.data && queue.data.items.length === 0 && <p className="text-slate-600">Nobody is waiting.</p>}
      {queue.data && queue.data.items.length > 0 && (
        <ul aria-label="Queue" className="space-y-2">
          {queue.data.items.map((e) => (
            <li key={e.appointmentId} className="flex items-center gap-4 rounded bg-white p-3 shadow">
              <span aria-label="Token" className="w-12 text-center text-2xl font-semibold">{e.tokenNo}</span>
              <span className="flex-1">
                <span className="font-medium">{e.patient.name ?? 'Patient'}</span>{' '}
                <span className="text-sm text-slate-600">{e.patient.uhid}</span>
              </span>
              <span className="rounded bg-slate-200 px-2 py-0.5 text-xs">{STATUS_LABELS[e.status]}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
