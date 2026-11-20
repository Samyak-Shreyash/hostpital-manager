import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { appointmentKeys, bookAppointment, walkIn, type Appointment } from '../../api/appointments'
import { ApiError } from '../../api/client'
import type { PatientSummary } from '../../api/patients'
import { Dialog } from '../../components/Dialog'
import { Field, FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { ConflictNotice } from '../practitioners/shared'
import { PatientPicker } from './shared'

const reasonField = z.string().trim().max(500, 'Reason is at most 500 characters')

const bookSchema = z.object({
  patientId: z.string().min(1, 'Select a patient'),
  reason: reasonField,
  durationMinutes: z.string().trim().refine((v) => /^\d{1,3}$/.test(v) && Number(v) >= 1 && Number(v) <= 480, 'Duration must be 1 to 480 minutes'),
})
type BookValues = z.infer<typeof bookSchema>

const walkInSchema = z.object({ patientId: z.string().min(1, 'Select a patient'), reason: reasonField })
type WalkInValues = z.infer<typeof walkInSchema>

/** Shows a failed mutation: a reload prompt for CONCURRENT_MODIFICATION, else the mapped message inline. */
export function MutationProblem({ error, what, onReload }: { error: unknown; what: string; onReload: () => void }) {
  if (!error) return null
  if (error instanceof ApiError && error.code === 'CONCURRENT_MODIFICATION') return <ConflictNotice what={what} onReload={onReload} />
  return <FormError message={errorMessage(error)} />
}

/** Books a free slot ([E5-1]). SLOT_CONFLICT is shown inline and the dialog stays open. */
export function BookAppointmentDialog({ doctorId, startAt, durationMinutes, timeLabel, onClose }: {
  doctorId: string; startAt: string; durationMinutes: number; timeLabel: string; onClose: () => void
}) {
  const queryClient = useQueryClient()
  const [patient, setPatient] = useState<PatientSummary | null>(null)
  const form = useForm<BookValues>({ resolver: zodResolver(bookSchema), defaultValues: { patientId: '', reason: '', durationMinutes: String(durationMinutes) } })
  const reload = () => { void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }); onClose() }
  const book = useMutation({
    mutationFn: (v: BookValues) => bookAppointment({
      patientId: v.patientId, doctorId, startAt, durationMinutes: Number(v.durationMinutes), ...(v.reason ? { reason: v.reason } : {}),
    }),
    onSuccess: reload,
    // A taken or changed slot: refresh the free slots behind the dialog too.
    onError: () => { void queryClient.invalidateQueries({ queryKey: ['doctors', 'detail', doctorId, 'slots'] }) },
  })
  const { errors } = form.formState
  return (
    <Dialog title="Book appointment" onClose={onClose}>
      <p className="mb-3 text-sm text-slate-600">Slot at {timeLabel}</p>
      <form noValidate onSubmit={form.handleSubmit((v) => book.mutate(v))}>
        <PatientPicker selected={patient} error={errors.patientId?.message} onSelect={(p) => { setPatient(p); form.setValue('patientId', p.id, { shouldValidate: true }) }} />
        <Field label="Duration (minutes)" htmlFor="book-duration" error={errors.durationMinutes?.message}>
          <input id="book-duration" inputMode="numeric" className={inputClass} {...form.register('durationMinutes')} />
        </Field>
        <Field label="Reason" htmlFor="book-reason" error={errors.reason?.message}>
          <textarea id="book-reason" rows={2} className={inputClass} {...form.register('reason')} />
        </Field>
        <MutationProblem error={book.error} what="schedule" onReload={reload} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={book.isPending}>Confirm booking</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Close</button>
        </div>
      </form>
    </Dialog>
  )
}

/** A walk-in takes a token at once and holds no slot ([E5-3]); the token is reported through `onDone`. */
export function WalkInDialog({ doctorId, onDone, onClose }: { doctorId: string; onDone: (a: Appointment) => void; onClose: () => void }) {
  const queryClient = useQueryClient()
  const [patient, setPatient] = useState<PatientSummary | null>(null)
  const form = useForm<WalkInValues>({ resolver: zodResolver(walkInSchema), defaultValues: { patientId: '', reason: '' } })
  const register = useMutation({
    mutationFn: (v: WalkInValues) => walkIn({ patientId: v.patientId, doctorId, ...(v.reason ? { reason: v.reason } : {}) }),
    onSuccess: (a) => { void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }); onDone(a); onClose() },
  })
  const { errors } = form.formState
  return (
    <Dialog title="Walk-in" onClose={onClose}>
      <form noValidate onSubmit={form.handleSubmit((v) => register.mutate(v))}>
        <PatientPicker selected={patient} error={errors.patientId?.message} onSelect={(p) => { setPatient(p); form.setValue('patientId', p.id, { shouldValidate: true }) }} />
        <Field label="Reason" htmlFor="walkin-reason" error={errors.reason?.message}>
          <textarea id="walkin-reason" rows={2} className={inputClass} {...form.register('reason')} />
        </Field>
        <FormError message={register.error ? errorMessage(register.error) : null} />
        <div className="flex gap-2">
          <button type="submit" className={primaryButton} disabled={register.isPending}>Register walk-in</button>
          <button type="button" className={secondaryButton} onClick={onClose}>Close</button>
        </div>
      </form>
    </Dialog>
  )
}
