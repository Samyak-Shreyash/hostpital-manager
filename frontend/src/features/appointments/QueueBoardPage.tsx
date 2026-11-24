import { useQueries, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { appointmentKeys, getQueue, type Queue, type QueueEntry } from '../../api/appointments'
import type { Doctor } from '../../api/practitioners'
import { inputClass } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { StartConsult } from '../consultation/StartConsult'
import { DoctorSelect, STATUS_LABELS, useActiveDoctors } from './shared'

/** How often the board re-reads the live queue (api-design.md section 7: polling, no push). */
export const QUEUE_POLL_MS = 15_000

const CHIP: Partial<Record<QueueEntry['status'], string>> = {
  IN_CONSULTATION: 'bg-primary text-white',
  CHECKED_IN: 'bg-primary-soft text-primary',
}

function nowServing(q?: Queue) {
  return q?.items.find((e) => e.status === 'IN_CONSULTATION')
}

/** One card per active doctor: the token in consultation and who is next. Choosing a card opens that doctor's queue. */
function Overview({ doctors, date, onPick }: { doctors: Doctor[]; date: string; onPick: (id: string) => void }) {
  const queues = useQueries({
    queries: doctors.map((d) => ({
      queryKey: appointmentKeys.queue(d.id, date),
      queryFn: () => getQueue(d.id, date || undefined),
      refetchInterval: QUEUE_POLL_MS,
      staleTime: QUEUE_POLL_MS,
    })),
  })
  if (doctors.length === 0) return <p className="text-ink-muted">Choose a doctor to see their queue.</p>
  return (
    <ul aria-label="Doctors' queues" className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-3">
      {doctors.map((d, i) => {
        const q = queues[i]
        const now = nowServing(q.data)
        const waiting = q.data?.items.filter((e) => e.status === 'CHECKED_IN') ?? []
        return (
          <li key={d.id} className="flex flex-col rounded-xl border border-border bg-surface">
            <div className="flex items-start gap-3 border-b border-border p-4">
              <div className="min-w-0 flex-1">
                <h2 className="text-base font-semibold">{d.fullName}</h2>
                <p className="text-sm text-ink-muted">{waiting.length} waiting</p>
              </div>
              <div className="rounded-lg bg-nav px-3 py-1 text-center text-white">
                <div className="text-[11px] text-nav-ink-muted">NOW</div>
                <div className="font-display text-2xl font-bold leading-none">{now ? now.tokenNo : '–'}</div>
              </div>
            </div>
            <div className="flex flex-1 flex-wrap content-start gap-1.5 p-4">
              {q.isError && <p role="alert" className="text-sm text-danger">{errorMessage(q.error)}</p>}
              {q.isPending && <p className="text-sm text-ink-muted">Loading...</p>}
              {q.data && waiting.length === 0 && <p className="text-sm text-ink-muted">Nobody is waiting.</p>}
              {waiting.slice(0, 8).map((e) => (
                <span key={e.appointmentId} className="rounded-full bg-muted px-2.5 py-1 text-xs font-semibold">
                  #{e.tokenNo} {e.patient.name ?? 'Patient'}
                </span>
              ))}
            </div>
            <button type="button" className="min-h-11 border-t border-border text-sm font-semibold text-primary hover:bg-primary-soft"
              onClick={() => onPick(d.id)} aria-label={`Open queue for ${d.fullName}`}>
              Open queue
            </button>
          </li>
        )
      })}
    </ul>
  )
}

/** The doctors' live queues by token. The date is optional: the server defaults to today in the facility timezone. */
export function QueueBoardPage() {
  const { doctors } = useActiveDoctors()
  const [doctorId, setDoctorId] = useState('')
  const [date, setDate] = useState('')
  const queue = useQuery({
    queryKey: appointmentKeys.queue(doctorId, date),
    queryFn: () => getQueue(doctorId, date || undefined),
    enabled: !!doctorId,
    refetchInterval: QUEUE_POLL_MS,
    staleTime: QUEUE_POLL_MS,
  })
  const now = nowServing(queue.data)
  return (
    <section className="flex flex-col gap-5">
      <div>
        <h1 className="text-3xl font-semibold">OPD queue</h1>
        <p className="text-sm text-ink-muted">Updates every {QUEUE_POLL_MS / 1000} seconds.</p>
      </div>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
        <DoctorSelect value={doctorId} onChange={setDoctorId} />
        <div className="text-sm">
          <label htmlFor="queue-date" className="block font-medium">Date</label>
          <input id="queue-date" type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
          <p className="mt-1 text-ink-muted">Leave empty for today.</p>
        </div>
      </div>
      {!doctorId && <Overview doctors={doctors} date={date} onPick={setDoctorId} />}
      {queue.isError && <p role="alert" className="text-danger">{errorMessage(queue.error)}</p>}
      {doctorId && queue.isPending && !queue.isError && <p role="status">Loading...</p>}
      {queue.data && (
        <div className="flex items-center gap-4 rounded-xl bg-primary-soft p-4">
          <span className="text-sm text-ink-muted">Now serving</span>
          <span className="font-display text-4xl font-bold text-primary">{now ? now.tokenNo : '–'}</span>
          <button type="button" className="ml-auto text-sm font-semibold text-primary underline" onClick={() => setDoctorId('')}>All doctors</button>
        </div>
      )}
      {queue.data && queue.data.items.length === 0 && <p className="text-ink-muted">Nobody is waiting.</p>}
      {queue.data && queue.data.items.length > 0 && (
        <ul aria-label="Queue" className="overflow-hidden rounded-xl border border-border bg-surface">
          {queue.data.items.map((e) => (
            <li key={e.appointmentId} className="flex flex-wrap items-center gap-4 border-b border-border p-3 last:border-b-0">
              <span aria-label="Token" className="w-14 text-center font-display text-2xl font-bold">{e.tokenNo}</span>
              <span className="flex-1">
                <span className="font-medium">{e.patient.name ?? 'Patient'}</span>{' '}
                <span className="text-sm text-ink-muted">{e.patient.uhid}</span>
              </span>
              <span className={`rounded-full px-2.5 py-1 text-xs font-semibold ${CHIP[e.status] ?? 'bg-muted'}`}>{STATUS_LABELS[e.status]}</span>
              <StartConsult entry={e} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
