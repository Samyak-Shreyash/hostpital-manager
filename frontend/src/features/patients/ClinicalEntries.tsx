import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import {
  addAllergy,
  addHistory,
  markAllergyInError,
  markHistoryInError,
  patientKeys,
  type Allergy,
  type HistoryEntry,
  type Severity,
} from '../../api/patients'
import { FormError, inputClass, secondaryButton } from '../../components/ui'
import { errorMessage } from '../auth/messages'

const statusBadge = (status: string) =>
  status === 'ENTERED_IN_ERROR' ? <span className="ml-2 rounded bg-slate-200 px-1 text-xs">Entered in error</span> : null

/** Allergies list with add / entered-in-error actions (patient:update-clinical). */
export function AllergyList({ patientId, allergies, canEdit }: { patientId: string; allergies: Allergy[]; canEdit: boolean }) {
  const queryClient = useQueryClient()
  const refresh = () => void queryClient.invalidateQueries({ queryKey: patientKeys.allergies(patientId) })
  const [substance, setSubstance] = useState('')
  const [reaction, setReaction] = useState('')
  const [severity, setSeverity] = useState<Severity>('MODERATE')
  const [formError, setFormError] = useState<string | null>(null)
  const add = useMutation({
    mutationFn: () => addAllergy(patientId, { substance: substance.trim(), reaction: reaction.trim() || undefined, severity }),
    onSuccess: () => { setSubstance(''); setReaction(''); refresh() },
  })
  const mark = useMutation({ mutationFn: (entryId: string) => markAllergyInError(patientId, entryId), onSuccess: refresh })

  return (
    <div className="mb-6">
      <h2 className="mb-2 text-lg font-semibold">Allergies</h2>
      <FormError message={add.isError ? errorMessage(add.error) : mark.isError ? errorMessage(mark.error) : formError} />
      <ul className="mb-3 text-sm">
        {allergies.length === 0 && <li>No allergies recorded.</li>}
        {allergies.map((a) => (
          <li key={a.id} className={`mb-1 ${a.status === 'ENTERED_IN_ERROR' ? 'text-slate-500 line-through' : ''}`}>
            <span className="font-medium">{a.substance}</span> ({a.severity.toLowerCase()}){a.reaction ? ` - ${a.reaction}` : ''}
            {statusBadge(a.status)}
            {canEdit && a.status === 'ACTIVE' && (
              <button type="button" className="ml-3 text-xs text-blue-700 underline" disabled={mark.isPending}
                aria-label={`Mark allergy ${a.substance} as entered in error`} onClick={() => mark.mutate(a.id)}>
                Entered in error
              </button>
            )}
          </li>
        ))}
      </ul>
      {canEdit && (
        <form
          noValidate
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (!substance.trim()) { setFormError('Substance is required'); return }
            setFormError(null)
            add.mutate()
          }}
        >
          <label className="text-sm">Substance<input className={inputClass} maxLength={200} value={substance} onChange={(e) => setSubstance(e.target.value)} /></label>
          <label className="text-sm">Reaction<input className={inputClass} maxLength={1000} value={reaction} onChange={(e) => setReaction(e.target.value)} /></label>
          <label className="text-sm">Severity
            <select className={inputClass} value={severity} onChange={(e) => setSeverity(e.target.value as Severity)}>
              <option value="MILD">Mild</option><option value="MODERATE">Moderate</option><option value="SEVERE">Severe</option>
            </select>
          </label>
          <button type="submit" className={secondaryButton} disabled={add.isPending}>Add allergy</button>
        </form>
      )}
    </div>
  )
}

/** Medical history list with add / entered-in-error actions (patient:update-clinical). */
export function HistoryList({ patientId, entries, canEdit }: { patientId: string; entries: HistoryEntry[]; canEdit: boolean }) {
  const queryClient = useQueryClient()
  const refresh = () => void queryClient.invalidateQueries({ queryKey: patientKeys.history(patientId) })
  const [condition, setCondition] = useState('')
  const [notes, setNotes] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const add = useMutation({
    mutationFn: () => addHistory(patientId, { condition: condition.trim(), notes: notes.trim() || undefined }),
    onSuccess: () => { setCondition(''); setNotes(''); refresh() },
  })
  const mark = useMutation({ mutationFn: (entryId: string) => markHistoryInError(patientId, entryId), onSuccess: refresh })

  return (
    <div className="mb-6">
      <h2 className="mb-2 text-lg font-semibold">Medical history</h2>
      <FormError message={add.isError ? errorMessage(add.error) : mark.isError ? errorMessage(mark.error) : formError} />
      <ul className="mb-3 text-sm">
        {entries.length === 0 && <li>No medical history recorded.</li>}
        {entries.map((h) => (
          <li key={h.id} className={`mb-1 ${h.status === 'ENTERED_IN_ERROR' ? 'text-slate-500 line-through' : ''}`}>
            <span className="font-medium">{h.condition}</span>{h.notes ? ` - ${h.notes}` : ''}
            {statusBadge(h.status)}
            {canEdit && h.status === 'ACTIVE' && (
              <button type="button" className="ml-3 text-xs text-blue-700 underline" disabled={mark.isPending}
                aria-label={`Mark history ${h.condition} as entered in error`} onClick={() => mark.mutate(h.id)}>
                Entered in error
              </button>
            )}
          </li>
        ))}
      </ul>
      {canEdit && (
        <form
          noValidate
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault()
            if (!condition.trim()) { setFormError('Condition is required'); return }
            setFormError(null)
            add.mutate()
          }}
        >
          <label className="text-sm">Condition<input className={inputClass} maxLength={200} value={condition} onChange={(e) => setCondition(e.target.value)} /></label>
          <label className="text-sm">Notes<input className={inputClass} maxLength={1000} value={notes} onChange={(e) => setNotes(e.target.value)} /></label>
          <button type="submit" className={secondaryButton} disabled={add.isPending}>Add history</button>
        </form>
      )}
    </div>
  )
}
