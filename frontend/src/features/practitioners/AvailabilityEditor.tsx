import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/client'
import { getAvailability, practitionerKeys, replaceAvailability, type Availability, type Window } from '../../api/practitioners'
import { FormError, inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'
import { WEEKDAYS, windowProblems } from './schemas'
import { ConflictNotice } from './shared'

const sorted = (w: Window[]) => [...w].sort((a, b) => a.weekday - b.weekday || a.start.localeCompare(b.start))

/** Weekly template (local times in the organization timezone); saving replaces the whole template. */
export function AvailabilityEditor({ doctorId, canEdit }: { doctorId: string; canEdit: boolean }) {
  const availability = useQuery({ queryKey: practitionerKeys.availability(doctorId), queryFn: () => getAvailability(doctorId) })
  if (availability.isPending) return <p role="status">Loading availability...</p>
  if (availability.isError) return <p role="alert" className="text-red-700">{errorMessage(availability.error)}</p>
  return (
    <TemplateEditor
      key={availability.data.version}
      doctorId={doctorId}
      availability={availability.data}
      canEdit={canEdit}
      onReload={() => void availability.refetch()}
    />
  )
}

function TemplateEditor({ doctorId, availability, canEdit, onReload }: { doctorId: string; availability: Availability; canEdit: boolean; onReload: () => void }) {
  const queryClient = useQueryClient()
  const [windows, setWindows] = useState<Window[]>(sorted(availability.windows))
  const [problems, setProblems] = useState<string[]>([])
  const [saved, setSaved] = useState(false)
  const save = useMutation({
    mutationFn: (w: Window[]) => replaceAvailability(doctorId, { version: availability.version, windows: w }),
    onSuccess: (updated) => {
      setSaved(true)
      queryClient.setQueryData(practitionerKeys.availability(doctorId), updated)
      void queryClient.invalidateQueries({ queryKey: practitionerKeys.doctor(doctorId), exact: true })
    },
  })
  const conflict = save.error instanceof ApiError && save.error.code === 'CONCURRENT_MODIFICATION'
  const update = (i: number, patch: Partial<Window>) => setWindows(windows.map((w, j) => (j === i ? { ...w, ...patch } : w)))
  const submit = () => {
    setSaved(false)
    const found = windowProblems(windows)
    setProblems(found)
    if (found.length === 0) save.mutate(windows)
  }

  return (
    <section aria-labelledby="availability-heading" className="mb-8">
      <h2 id="availability-heading" className="mb-1 text-lg font-semibold">Weekly availability</h2>
      <p className="mb-3 text-sm text-slate-600">Times are in the facility timezone ({availability.timezone}).</p>
      {saved && <p role="status" className="mb-2 text-sm text-green-800">Availability saved.</p>}
      {conflict && <ConflictNotice what="doctor" onReload={() => { save.reset(); onReload() }} />}
      <FormError message={save.isError && !conflict ? errorMessage(save.error) : null} />
      {problems.length > 0 && (
        <ul role="alert" className="mb-3 list-disc rounded bg-red-50 p-3 pl-6 text-sm text-red-800">
          {problems.map((p) => <li key={p}>{p}</li>)}
        </ul>
      )}
      {windows.length === 0 && <p className="mb-3 text-sm text-slate-600">No weekly availability defined.</p>}
      {windows.length > 0 && (
        <table className="mb-3 w-full border-collapse bg-white text-left text-sm shadow">
          <thead>
            <tr className="border-b bg-slate-100">
              <th className="p-2">#</th><th className="p-2">Day</th><th className="p-2">Start</th><th className="p-2">End</th><th className="p-2">Slot (min)</th>
              {canEdit && <th className="p-2"><span className="sr-only">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {windows.map((w, i) => (
              <tr key={i} className="border-b">
                <td className="p-2">{i + 1}</td>
                {canEdit ? (
                  <>
                    <td className="p-2">
                      <select aria-label={`Day ${i + 1}`} className={inputClass} value={w.weekday} onChange={(e) => update(i, { weekday: Number(e.target.value) })}>
                        {WEEKDAYS.map((name, d) => <option key={name} value={d + 1}>{name}</option>)}
                      </select>
                    </td>
                    <td className="p-2"><input aria-label={`Start ${i + 1}`} type="time" className={inputClass} value={w.start} onChange={(e) => update(i, { start: e.target.value })} /></td>
                    <td className="p-2"><input aria-label={`End ${i + 1}`} type="time" className={inputClass} value={w.end} onChange={(e) => update(i, { end: e.target.value })} /></td>
                    <td className="p-2">
                      <input aria-label={`Slot ${i + 1}`} inputMode="numeric" className={inputClass} value={Number.isNaN(w.slotMinutes) ? '' : w.slotMinutes}
                        onChange={(e) => update(i, { slotMinutes: Number(e.target.value) })} />
                    </td>
                    <td className="p-2">
                      <button type="button" className="text-red-700 underline" onClick={() => setWindows(windows.filter((_, j) => j !== i))}>Remove</button>
                    </td>
                  </>
                ) : (
                  <>
                    <td className="p-2">{WEEKDAYS[w.weekday - 1]}</td><td className="p-2">{w.start}</td><td className="p-2">{w.end}</td><td className="p-2">{w.slotMinutes}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {canEdit && (
        <div className="flex gap-3">
          <button type="button" className={secondaryButton} onClick={() => setWindows([...windows, { weekday: 1, start: '09:00', end: '13:00', slotMinutes: 15 }])}>
            Add window
          </button>
          <button type="button" className={primaryButton} disabled={save.isPending} onClick={submit}>Save availability</button>
        </div>
      )}
    </section>
  )
}
