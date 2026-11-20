import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { appointmentKeys, cancelAppointment, rescheduleAppointment, type Appointment } from '../../api/appointments'
import { Dialog } from '../../components/Dialog'
import { Field, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { formatTime } from '../practitioners/schemas'
import { MutationProblem } from './BookAppointmentDialog'

const cancelSchema = z.object({ reason: z.string().trim().min(1, 'Enter a reason for cancelling').max(500, 'Reason is at most 500 characters') })
type CancelValues = z.infer<typeof cancelSchema>

/** Cancel needs a reason (1-500 characters) and the appointment's version. */
export function CancelDialog({ appointment, onClose }: { appointment: Appointment; onClose: () => void }) {
  const queryClient = useQueryClient()
  const reload = () => { void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }); onClose() }
  const form = useForm<CancelValues>({ resolver: zodResolver(cancelSchema), defaultValues: { reason: '' } })
  const cancel = useMutation({
    mutationFn: (v: CancelValues) => cancelAppointment(appointment.id, { version: appointment.version, reason: v.reason }),
    onSuccess: reload,
  })
  return (
    <Dialog title="Cancel appointment" onClose={onClose}>
      <form noValidate onSubmit={form.handleSubmit((v) => cancel.mutate(v))}>
        <Field label="Reason" htmlFor="cancel-reason" error={form.formState.errors.reason?.message}>
          <textarea id="cancel-reason" rows={3} className={inputClass} {...form.register('reason')} />
        </Field>
        <MutationProblem error={cancel.error} what="appointment" onReload={reload} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={cancel.isPending}>Confirm cancellation</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Keep appointment</button>
        </div>
      </form>
    </Dialog>
  )
}

const rescheduleSchema = z.object({ startAt: z.string().min(1, 'Choose a new time') })

/** Moves a SCHEDULED appointment to another free slot of the same doctor; the server creates the successor. */
export function RescheduleDialog({ appointment, slots, timeZone, onClose }: {
  appointment: Appointment; slots: { startAt: string; endAt: string }[]; timeZone: string; onClose: () => void
}) {
  const queryClient = useQueryClient()
  const reload = () => { void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }); onClose() }
  const form = useForm<{ startAt: string }>({ resolver: zodResolver(rescheduleSchema), defaultValues: { startAt: '' } })
  const move = useMutation({
    mutationFn: (v: { startAt: string }) => {
      const slot = slots.find((s) => s.startAt === v.startAt)
      const minutes = slot ? Math.round((Date.parse(slot.endAt) - Date.parse(slot.startAt)) / 60_000) : appointment.durationMinutes
      return rescheduleAppointment(appointment.id, { version: appointment.version, startAt: v.startAt, durationMinutes: minutes })
    },
    onSuccess: reload,
    onError: () => { void queryClient.invalidateQueries({ queryKey: ['doctors', 'detail', appointment.doctorId, 'slots'] }) },
  })
  return (
    <Dialog title="Reschedule appointment" onClose={onClose}>
      <form noValidate onSubmit={form.handleSubmit((v) => move.mutate(v))}>
        <Field label="New time" htmlFor="resched-time" error={form.formState.errors.startAt?.message}>
          <select id="resched-time" className={inputClass} {...form.register('startAt')}>
            <option value="">Select a free slot</option>
            {slots.map((s) => <option key={s.startAt} value={s.startAt}>{formatTime(s.startAt, timeZone)}–{formatTime(s.endAt, timeZone)}</option>)}
          </select>
        </Field>
        {slots.length === 0 && <p className="mb-3 text-sm text-slate-600">No free slots on this day. Pick another date first.</p>}
        <MutationProblem error={move.error} what="appointment" onReload={reload} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={move.isPending}>Confirm reschedule</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Close</button>
        </div>
      </form>
    </Dialog>
  )
}
