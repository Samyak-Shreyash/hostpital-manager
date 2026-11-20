import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { appointmentKeys, checkInAppointment, listAppointments, markNoShow, type Appointment } from '../../api/appointments'
import { getSlots, practitionerKeys } from '../../api/practitioners'
import { inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useCan, useSession } from '../auth/useSession'
import { formatTime } from '../practitioners/schemas'
import { BookAppointmentDialog, MutationProblem, WalkInDialog } from './BookAppointmentDialog'
import { CancelDialog, RescheduleDialog } from './ChangeDialogs'
import { DoctorSelect, PatientName, STATUS_LABELS, todayIn } from './shared'

type Slot = { startAt: string; endAt: string }
type Row = { kind: 'slot'; at: string; slot: Slot } | { kind: 'appointment'; at: string; appointment: Appointment }

/**
 * One doctor's day: free slots (already net of bookings, review notes Q9) with the day's appointments overlaid,
 * in the facility timezone. Booking, walk-in, check-in, reschedule and cancel act from here; each button needs
 * its own permission.
 */
export function DayCalendarPage() {
  const session = useSession()
  const timeZone = session.data?.organization.timezone ?? 'UTC'
  const queryClient = useQueryClient()
  const canCreate = useCan('appointment:create')
  const canUpdate = useCan('appointment:update')
  const canCancel = useCan('appointment:cancel')
  const canCheckIn = useCan('appointment:check-in')
  const [doctorId, setDoctorId] = useState('')
  const [date, setDate] = useState(() => todayIn(timeZone))
  const [booking, setBooking] = useState<Slot | null>(null)
  const [walkInOpen, setWalkInOpen] = useState(false)
  const [cancelling, setCancelling] = useState<Appointment | null>(null)
  const [rescheduling, setRescheduling] = useState<Appointment | null>(null)
  const [result, setResult] = useState<{ label: string; appointment: Appointment } | null>(null)

  const slots = useQuery({
    queryKey: practitionerKeys.slots(doctorId, date, date),
    queryFn: () => getSlots(doctorId, date, date),
    enabled: !!doctorId && !!date,
  })
  const appointments = useQuery({
    queryKey: appointmentKeys.day(doctorId, date),
    queryFn: () => listAppointments({ doctorId, date, size: 100 }),
    enabled: !!doctorId && !!date,
  })
  const checkIn = useMutation({
    mutationFn: (a: Appointment) => checkInAppointment(a.id, a.version),
    onSuccess: (a) => { setResult({ label: 'Checked in', appointment: a }); void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }) },
  })
  const noShow = useMutation({
    mutationFn: (a: Appointment) => markNoShow(a.id, a.version),
    onSuccess: () => { void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }) },
  })
  const reloadAll = () => {
    void queryClient.invalidateQueries({ queryKey: appointmentKeys.all })
    void queryClient.invalidateQueries({ queryKey: ['doctors', 'detail', doctorId, 'slots'] })
  }

  const zone = slots.data?.timezone ?? timeZone
  const freeSlots = useMemo(() => slots.data?.slots ?? [], [slots.data])
  const rows: Row[] = useMemo(() => {
    const list: Row[] = [
      ...freeSlots.map((slot): Row => ({ kind: 'slot', at: slot.startAt, slot })),
      ...(appointments.data?.items ?? []).map((appointment): Row => ({ kind: 'appointment', at: appointment.startAt, appointment })),
    ]
    return list.sort((a, b) => Date.parse(a.at) - Date.parse(b.at))
  }, [freeSlots, appointments.data])
  const slotLabel = (s: Slot) => `${formatTime(s.startAt, zone)}–${formatTime(s.endAt, zone)}`
  const actionError = checkIn.error ?? noShow.error
  const loaded = !!doctorId && !!slots.data && !!appointments.data

  return (
    <section>
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Appointments</h1>
        {canCreate && <button type="button" className={primaryButton} disabled={!doctorId} onClick={() => setWalkInOpen(true)}>Walk-in</button>}
      </div>
      <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
        <DoctorSelect value={doctorId} onChange={(id) => { setDoctorId(id); setResult(null) }} />
        <div className="text-sm">
          <label htmlFor="appt-date" className="block font-medium">Date</label>
          <input id="appt-date" type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
        </div>
      </div>
      <p className="mb-3 text-sm text-slate-600">Times are shown in the facility timezone ({zone}).</p>

      {result && (
        <p role="status" aria-label="Check-in result" className="mb-4 rounded bg-green-50 p-3 text-green-900">
          {result.label}: <strong>Token {result.appointment.tokenNo}</strong> <PatientName patientId={result.appointment.patientId} />
        </p>
      )}
      <MutationProblem error={actionError} what="appointment" onReload={reloadAll} />
      {(slots.isError || appointments.isError) && <p role="alert" className="text-red-700">{errorMessage(slots.error ?? appointments.error)}</p>}
      {!doctorId && <p className="text-slate-600">Choose a doctor to see their day.</p>}
      {doctorId && !loaded && !slots.isError && !appointments.isError && <p role="status">Loading...</p>}
      {loaded && rows.length === 0 && <p className="text-slate-600">No free slots or appointments on this day.</p>}
      {loaded && rows.length > 0 && (
        <ul aria-label="Day schedule" className="space-y-2">
          {rows.map((row) => row.kind === 'slot' ? (
            <li key={`s-${row.at}`} className="flex items-center justify-between rounded border border-dashed border-slate-300 bg-white p-3 text-sm">
              <span>{slotLabel(row.slot)} <span className="text-slate-500">free</span></span>
              {canCreate && (
                <button type="button" className={secondaryButton} aria-label={`Book ${formatTime(row.slot.startAt, zone)}`} onClick={() => setBooking(row.slot)}>Book</button>
              )}
            </li>
          ) : (
            <AppointmentRow
              key={`a-${row.appointment.id}`} appointment={row.appointment} zone={zone}
              canCheckIn={canCheckIn} canUpdate={canUpdate} canCancel={canCancel} busy={checkIn.isPending || noShow.isPending}
              onCheckIn={() => checkIn.mutate(row.appointment)} onNoShow={() => noShow.mutate(row.appointment)}
              onCancel={() => setCancelling(row.appointment)} onReschedule={() => setRescheduling(row.appointment)}
            />
          ))}
        </ul>
      )}

      {booking && (
        <BookAppointmentDialog
          doctorId={doctorId} startAt={booking.startAt} timeLabel={slotLabel(booking)} onClose={() => setBooking(null)}
          durationMinutes={Math.round((Date.parse(booking.endAt) - Date.parse(booking.startAt)) / 60_000)}
        />
      )}
      {walkInOpen && <WalkInDialog doctorId={doctorId} onDone={(a) => setResult({ label: 'Walk-in registered', appointment: a })} onClose={() => setWalkInOpen(false)} />}
      {cancelling && <CancelDialog appointment={cancelling} onClose={() => setCancelling(null)} />}
      {rescheduling && <RescheduleDialog appointment={rescheduling} slots={freeSlots} timeZone={zone} onClose={() => setRescheduling(null)} />}
    </section>
  )
}

function AppointmentRow({ appointment: a, zone, canCheckIn, canUpdate, canCancel, busy, onCheckIn, onNoShow, onCancel, onReschedule }: {
  appointment: Appointment; zone: string; canCheckIn: boolean; canUpdate: boolean; canCancel: boolean; busy: boolean
  onCheckIn: () => void; onNoShow: () => void; onCancel: () => void; onReschedule: () => void
}) {
  const scheduled = a.status === 'SCHEDULED'
  const dimmed = a.status === 'CANCELLED' || a.status === 'NO_SHOW' || a.status === 'RESCHEDULED'
  return (
    <li className={`rounded bg-white p-3 text-sm shadow ${dimmed ? 'opacity-60' : ''}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <span className="mr-2 font-medium">{formatTime(a.startAt, zone)}–{formatTime(a.endAt, zone)}</span>
          <PatientName patientId={a.patientId} />
          <span className="ml-2 rounded bg-slate-200 px-2 py-0.5 text-xs">{STATUS_LABELS[a.status]}</span>
          {a.type === 'WALK_IN' && <span className="ml-1 rounded bg-blue-100 px-2 py-0.5 text-xs">Walk-in</span>}
          {a.tokenNo !== null && <span className="ml-2">Token {a.tokenNo}</span>}
          {a.reason && <span className="ml-2 text-slate-600">{a.reason}</span>}
        </div>
        <div className="flex gap-2">
          {scheduled && canCheckIn && <button type="button" className={secondaryButton} disabled={busy} onClick={onCheckIn}>Check in</button>}
          {scheduled && canUpdate && <button type="button" className={secondaryButton} onClick={onReschedule}>Reschedule</button>}
          {scheduled && canUpdate && <button type="button" className={secondaryButton} disabled={busy} onClick={onNoShow}>No-show</button>}
          {(scheduled || a.status === 'CHECKED_IN') && canCancel && <button type="button" className={secondaryButton} onClick={onCancel}>Cancel appointment</button>}
        </div>
      </div>
    </li>
  )
}
