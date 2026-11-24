import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { addAddendum, finalizeEncounter } from '../../api/clinical'
import { appointmentKeys } from '../../api/appointments'
import { inputClass, primaryButton, secondaryButton } from '../../components/ui'
import { useCan } from '../auth/useSession'
import { ConsultProblem, Section, useEncounterSave, type SectionProps } from './shared'

/** Finalize locks the record (and completes the visit): it asks for one confirmation first. */
export function FinalizeSection(props: SectionProps) {
  const { encounter } = props
  const queryClient = useQueryClient()
  const canFinalize = useCan('encounter:finalize')
  const [confirming, setConfirming] = useState(false)
  const finalize = useEncounterSave({ ...props, onSaved: (view) => { props.onSaved(view); void queryClient.invalidateQueries({ queryKey: appointmentKeys.all }) } },
    () => finalizeEncounter(encounter.id, encounter.version))
  if (encounter.status !== 'OPEN' || !canFinalize) return null
  return (
    <Section title="Close the visit">
      {!confirming ? (
        <button type="button" className={primaryButton} onClick={() => setConfirming(true)}>Finalize encounter</button>
      ) : (
        <div>
          <p className="mb-2 text-sm">Finalizing locks vitals, notes, diagnoses and the plan. Later changes can only be added as addenda.</p>
          <div className="flex gap-2">
            <button type="button" className={primaryButton} disabled={finalize.isPending} onClick={() => finalize.mutate(undefined)}>Confirm finalize</button>
            <button type="button" className={secondaryButton} onClick={() => setConfirming(false)}>Cancel</button>
          </div>
        </div>
      )}
      <ConsultProblem error={finalize.error} onReload={props.onReload} />
    </Section>
  )
}

/** Addenda of a finalized encounter, and the form to add one. */
export function AddendaSection(props: SectionProps) {
  const { encounter } = props
  const canAdd = useCan('encounter:addendum')
  const [text, setText] = useState('')
  const [error, setError] = useState('')
  const add = useEncounterSave(props, (body: string) => addAddendum(encounter.id, body))
  if (encounter.status !== 'FINALIZED') return null
  const addenda = encounter.notes.filter((n) => n.kind === 'ADDENDUM')
  const submit = () => {
    if (!text.trim()) { setError('Enter the addendum text'); return }
    setError('')
    add.mutate(text, { onSuccess: () => setText('') })
  }
  return (
    <Section title="Addenda">
      {addenda.length === 0 && <p className="mb-2 text-sm text-ink-muted">No addenda.</p>}
      <ul className="mb-3 space-y-2">
        {addenda.map((n, i) => (
          <li key={`${n.createdAt}-${i}`} className="rounded border border-border p-2 text-sm">
            <p className="whitespace-pre-wrap">{n.text}</p>
            <p className="mt-1 text-xs text-ink-muted">{n.createdAt ? new Date(n.createdAt).toLocaleString() : ''}</p>
          </li>
        ))}
      </ul>
      {canAdd && (
        <>
          <div className="mb-3 text-sm">
            <label htmlFor="addendum-text" className="block font-medium">Addendum</label>
            <textarea id="addendum-text" rows={3} maxLength={10000} className={inputClass} value={text} onChange={(e) => setText(e.target.value)} />
            {error && <p role="alert" className="mt-1 text-danger">{error}</p>}
          </div>
          <ConsultProblem error={add.error} onReload={props.onReload} />
          <button type="button" className={primaryButton} disabled={add.isPending} onClick={submit}>Add addendum</button>
        </>
      )}
    </Section>
  )
}

