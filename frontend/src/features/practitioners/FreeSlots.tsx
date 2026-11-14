import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { getSlots, practitionerKeys } from '../../api/practitioners'
import { Field, inputClass, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { useSession } from '../auth/useSession'
import { formatInstant, formatTime, MAX_SLOT_RANGE_DAYS, slotRangeSchema, type SlotRangeValues } from './schemas'

/**
 * Free-slot preview. Slots arrive as UTC instants and are shown in the facility timezone (from the response,
 * else the session's organization), never the browser's. Bookings are not subtracted yet (review notes Q9).
 */
export function FreeSlots({ doctorId }: { doctorId: string }) {
  const session = useSession()
  const [range, setRange] = useState<SlotRangeValues | null>(null)
  const form = useForm<SlotRangeValues>({ resolver: zodResolver(slotRangeSchema), defaultValues: { from: '', to: '' } })
  const slots = useQuery({
    queryKey: practitionerKeys.slots(doctorId, range?.from ?? '', range?.to ?? ''),
    queryFn: () => getSlots(doctorId, range!.from, range!.to),
    enabled: !!range,
  })
  const { errors } = form.formState
  const timeZone = slots.data?.timezone ?? session.data?.organization.timezone ?? 'UTC'

  return (
    <section aria-labelledby="slots-heading" className="mb-8">
      <h2 id="slots-heading" className="mb-1 text-lg font-semibold">Free slots preview</h2>
      <p className="mb-3 text-sm text-slate-600">
        Up to {MAX_SLOT_RANGE_DAYS} days. Times are shown in the facility timezone ({timeZone}). Booked appointments are not subtracted yet.
      </p>
      <form noValidate className="grid grid-cols-1 items-end gap-x-3 sm:grid-cols-3" onSubmit={form.handleSubmit(setRange)}>
        <Field label="From date" htmlFor="slots-from" error={errors.from?.message}>
          <input id="slots-from" type="date" className={inputClass} {...form.register('from')} />
        </Field>
        <Field label="To date" htmlFor="slots-to" error={errors.to?.message}>
          <input id="slots-to" type="date" className={inputClass} {...form.register('to')} />
        </Field>
        <div className="mb-4"><button type="submit" className={secondaryButton}>Show free slots</button></div>
      </form>
      {slots.isError && <p role="alert" className="text-red-700">{errorMessage(slots.error)}</p>}
      {slots.isFetching && <p role="status">Loading...</p>}
      {slots.data && slots.data.slots.length === 0 && <p className="text-sm text-slate-600">No free slots in this range.</p>}
      {slots.data && slots.data.slots.length > 0 && (
        <ul aria-label="Free slots" className="grid grid-cols-1 gap-1 text-sm sm:grid-cols-2">
          {slots.data.slots.map((s) => (
            <li key={s.startAt} className="rounded bg-white p-2 shadow">
              {formatInstant(s.startAt, timeZone)}–{formatTime(s.endAt, timeZone)}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
