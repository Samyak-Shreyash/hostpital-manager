import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useForm } from 'react-hook-form'
import { addLeave, cancelLeave, listLeave, practitionerKeys, type LeaveException } from '../../api/practitioners'
import { Field, FormError, inputClass, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { leaveSchema, type LeaveValues } from './schemas'

/** Leave is never deleted, only cancelled (api-design.md section 5). */
export function LeaveList({ doctorId, canEdit }: { doctorId: string; canEdit: boolean }) {
  const queryClient = useQueryClient()
  const key = practitionerKeys.leave(doctorId)
  const leave = useQuery({ queryKey: key, queryFn: () => listLeave(doctorId) })
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: key })
    void queryClient.invalidateQueries({ queryKey: ['doctors', 'detail', doctorId, 'slots'] })
  }
  const form = useForm<LeaveValues>({ resolver: zodResolver(leaveSchema), defaultValues: { date: '', start: '', end: '' } })
  const add = useMutation({
    mutationFn: (v: LeaveValues) => addLeave(doctorId, v.start ? { date: v.date, start: v.start, end: v.end } : { date: v.date }),
    onSuccess: () => { form.reset(); refresh() },
  })
  const cancel = useMutation({ mutationFn: (l: LeaveException) => cancelLeave(doctorId, l.id, l.version), onSuccess: refresh })
  const { errors } = form.formState

  return (
    <section aria-labelledby="leave-heading" className="mb-8">
      <h2 id="leave-heading" className="mb-3 text-lg font-semibold">Leave</h2>
      {leave.isError && <p role="alert" className="text-red-700">{errorMessage(leave.error)}</p>}
      <FormError message={cancel.isError ? errorMessage(cancel.error) : null} />
      {leave.data && leave.data.length === 0 && <p className="mb-3 text-sm text-slate-600">No leave recorded.</p>}
      {leave.data && leave.data.length > 0 && (
        <ul className="mb-4 divide-y rounded bg-white text-sm shadow">
          {leave.data.map((l) => (
            <li key={l.id} className="flex items-center gap-3 p-2">
              <span>{l.date}</span>
              <span>{l.wholeDay ? 'Whole day' : `${l.start}–${l.end}`}</span>
              {l.status === 'CANCELLED' && <span className="rounded bg-slate-200 px-2 text-xs">Cancelled</span>}
              {canEdit && l.status === 'ACTIVE' && (
                <button type="button" className="ml-auto text-red-700 underline" disabled={cancel.isPending}
                  aria-label={`Cancel leave on ${l.date}`} onClick={() => cancel.mutate(l)}>
                  Cancel leave
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
      {canEdit && (
        <form noValidate className="grid grid-cols-1 items-end gap-x-3 sm:grid-cols-4" onSubmit={form.handleSubmit((v) => add.mutate(v))}>
          <div className="sm:col-span-4"><FormError message={add.isError ? errorMessage(add.error) : null} /></div>
          <Field label="Leave date" htmlFor="leave-date" error={errors.date?.message}>
            <input id="leave-date" type="date" className={inputClass} {...form.register('date')} />
          </Field>
          <Field label="From (optional)" htmlFor="leave-start" error={errors.start?.message}>
            <input id="leave-start" type="time" className={inputClass} {...form.register('start')} />
          </Field>
          <Field label="To (optional)" htmlFor="leave-end" error={errors.end?.message}>
            <input id="leave-end" type="time" className={inputClass} {...form.register('end')} />
          </Field>
          <div className="mb-4"><button type="submit" className={secondaryButton} disabled={add.isPending}>Add leave</button></div>
        </form>
      )}
    </section>
  )
}
